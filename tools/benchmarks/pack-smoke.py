"""End-to-end smoke with the pinned Modrinth pack and a separate offline Fabric client."""

import concurrent.futures
import hashlib
import http.cookiejar
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

from minecraft_launcher_lib import command, mod_loader


PACK = "https://cdn.modrinth.com/data/Jkb29YJU/versions/4SKGla61/COBBLEVERSE%201.7.42.mrpack"
PACK_SHA512 = (
    "9af64e0d3a092ab8d72d966cc4d45512e8d875685e804bea9233adb88f5f5d4c305"
    "78b77304f70fe77e9ef2ee01e487d652f3341279bcc1850ff2587ae8aa8a4"
)
FABRIC = "https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.18.4/1.1.0/server/jar"
GAME_ADDRESS = "127.0.0.1:25585"
PANEL_ADDRESS = "http://127.0.0.1:8765"
PLAYER = "CobbleSmoke"


def download(url, target, sha512=None):
    if not url.startswith("https://"):
        raise ValueError(f"Non-HTTPS download: {url}")
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_suffix(target.suffix + ".part")
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=45) as remote, temp.open("wb") as output:
                shutil.copyfileobj(remote, output)
            if sha512:
                with temp.open("rb") as content:
                    if hashlib.file_digest(content, "sha512").hexdigest() != sha512:
                        raise ValueError(f"SHA-512 mismatch: {target.name}")
            temp.replace(target)
            return
        except OSError:
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)


def safe_path(root, relative):
    target = (root / relative).resolve()
    if not target.is_relative_to(root):
        raise ValueError(f"Unsafe pack path: {relative}")
    return target


def install_pack(root, server, client):
    archive = root / "cobbleverse.mrpack"
    download(PACK, archive, PACK_SHA512)
    with zipfile.ZipFile(archive) as bundle:
        index = json.loads(bundle.read("modrinth.index.json"))
        if index["dependencies"] != {"minecraft": "1.21.1", "fabric-loader": "0.18.4"}:
            raise ValueError("Unexpected pack dependency versions")

        def install(file):
            relative = file["path"]
            path = safe_path(client, relative)
            download(file["downloads"][0], path, file["hashes"]["sha512"])
            if relative.startswith(("mods/", "datapacks/")) and file.get("env", {}).get("server") != "unsupported":
                other = safe_path(server, relative)
                other.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, other)

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(install, index["files"]))

        # Apply generic overrides first, then side-specific overrides.
        for folder, sides in (("overrides", (server, client)),
                              ("server-overrides", (server,)),
                              ("client-overrides", (client,))):
            for entry in bundle.infolist():
                if entry.is_dir() or not entry.filename.startswith(folder + "/"):
                    continue
                relative = entry.filename.split("/", 1)[1]
                for side in sides:
                    if side == server and not relative.startswith(("config/", "defaultconfigs/", "datapacks/", "mods/")):
                        continue
                    target = safe_path(side, relative)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with bundle.open(entry) as source, target.open("wb") as output:
                        shutil.copyfileobj(source, output)
    print(f"Installed {len(index['files'])} pinned pack files.")


def panel_smoke(server):
    token = (server / "smoke-world/generation-control/web-access.token").read_text().strip()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    login = urllib.request.Request(
        PANEL_ADDRESS + "/api/session", json.dumps({"token": token}).encode(),
        {"Content-Type": "application/json", "Origin": PANEL_ADDRESS},
    )
    with opener.open(login, timeout=8) as response:
        if not json.load(response)["authenticated"]:
            raise RuntimeError("Panel session was not authenticated")
    with opener.open(PANEL_ADDRESS + "/api/state", timeout=8) as response:
        if json.load(response)["world"] != "smoke-world":
            raise RuntimeError("Panel reported a different world")
    print("Authenticated panel state passed.")


def wait_for(process, log, marker, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        lines = log.read_text(encoding="utf-8", errors="replace")
        if marker in lines:
            return
        if process.poll() is not None:
            break
        time.sleep(2)
    raise RuntimeError(f"Expected marker {marker!r} in {log.name}; process exit: {process.poll()}")


def finish(process, stop=False):
    if process.poll() is None:
        if stop:
            try:
                process.stdin.write(b"stop\n")
                process.stdin.flush()
                process.wait(timeout=45)
                return
            except (BrokenPipeError, subprocess.TimeoutExpired):
                pass
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def run(root):
    server, client = root / "server", root / "client"
    server.mkdir()
    client.mkdir()
    install_pack(root, server, client)
    (manager,) = (root / "release").glob("*.jar")
    shutil.copy2(manager, server / "mods" / manager.name)
    for jar in (client / "mods").glob("*.jar"):
        with zipfile.ZipFile(jar) as mod:
            if "fabric.mod.json" in mod.namelist() and json.loads(mod.read("fabric.mod.json")).get("id") == "generation_control":
                raise RuntimeError("The manager must be absent from the client")

    # Only this disposable world binds to loopback and accepts the offline fixture account.
    (server / "eula.txt").write_text("eula=true\n")
    (server / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=25585\nonline-mode=false\n"
        "level-name=smoke-world\nlevel-seed=42\nview-distance=3\nsimulation-distance=3\n"
        "spawn-protection=0\n"
    )
    download(FABRIC, server / "fabric-server.jar")
    (client / "options.txt").write_text("renderDistance:3\nsimulationDistance:3\nmaxFps:20\n")
    # Smaller texture load for software rendering on the runner.
    overrides = client / "config/resourcepackoverrides.json"
    config = json.loads(overrides.read_text())
    config.update(default_packs=["vanilla", "fabric"], pack_overrides={})
    overrides.write_text(json.dumps(config))
    iris = client / "config/iris.properties"
    if iris.is_file():
        lines = [line for line in iris.read_text().splitlines() if not line.startswith("enableShaders=")]
        iris.write_text("\n".join([*lines, "enableShaders=false", ""]))

    loader = mod_loader.get_mod_loader("fabric")
    installed = loader.install("1.21.1", client, loader_version="0.18.4")
    args = command.get_minecraft_command(installed, client, {
        "username": PLAYER, "uuid": "3d0bbb3880e33a3d9a1384995b87f05a", "token": "0",
        "executablePath": "java", "jvmArguments": ["-Xmx4G", "-XX:ActiveProcessorCount=2"],
        "quickPlayMultiplayer": GAME_ADDRESS,
        "customResolution": True, "resolutionWidth": "960", "resolutionHeight": "540",
    })
    server_log = root / "server.log"
    with server_log.open("wb") as output:
        dedicated = subprocess.Popen(
            ["java", "-Xmx3G", "-XX:ActiveProcessorCount=2", "-jar", "fabric-server.jar", "nogui"],
            cwd=server, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
        )
        try:
            wait_for(dedicated, server_log, "Painel web local:", 300)
            panel_smoke(server)
            with (root / "client.log").open("wb") as output:
                player = subprocess.Popen(args, cwd=client, stdout=output, stderr=subprocess.STDOUT)
                try:
                    wait_for(player, server_log, PLAYER + " joined the game", 330)
                    print("Fabric client without the manager joined the dedicated server.")
                finally:
                    finish(player)
        finally:
            finish(dedicated, stop=True)


if __name__ == "__main__":
    run(Path(sys.argv[1]).resolve())
