"""End-to-end smoke with the pinned Modrinth pack and a separate offline Fabric client."""

import concurrent.futures
import hashlib
import http.cookiejar
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

from minecraft_launcher_lib import command, mod_loader

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_server_mods import prepare_server_mods


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
        index_bytes = bundle.read("modrinth.index.json")
        index = json.loads(index_bytes)
        if index["dependencies"] != {"minecraft": "1.21.1", "fabric-loader": "0.18.4"}:
            raise ValueError("Unexpected pack dependency versions")
        index_path = root / "modrinth.index.json"
        index_path.write_bytes(index_bytes)

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

    filtered = server / "mods-filtered"
    report = prepare_server_mods(server / "mods", index_path, filtered)
    original = server / "mods"
    unfiltered = server / "mods-unfiltered"
    if unfiltered.exists():
        raise RuntimeError("Unexpected existing unfiltered mods folder in the disposable smoke server")
    original.rename(unfiltered)
    filtered.rename(original)
    print(
        f"Installed {len(index['files'])} pinned pack files; dedicated server uses "
        f"{len(report['included'])} JARs and omits {len(report['client_only'])} client-only JARs."
    )


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
    deadline = time.monotonic() + 60
    while True:
        try:
            with opener.open(PANEL_ADDRESS + "/api/state", timeout=8) as response:
                state = json.load(response)
                if state["world"] != "smoke-world":
                    raise RuntimeError("Panel reported a different world")
            if "estimatedTps" in state:
                with opener.open(PANEL_ADDRESS + "/api/metrics", timeout=8) as response:
                    metrics = json.load(response)
                if not metrics.get("samples"):
                    raise RuntimeError("Diagnostics did not return a runtime sample")
                sample = metrics["samples"][-1]
                for field in ("estimatedTps", "tickMs", "heapUsedMb", "gcCollectionDelta", "gcTimeDeltaMs"):
                    if field not in sample:
                        raise RuntimeError(f"Diagnostics sample is missing {field}")
                print("On-demand diagnostics returned TPS estimate, MSPT, heap, and GC metrics.")
            print("Authenticated panel state passed.")
            return opener
        except urllib.error.HTTPError as failure:
            # RCT can register its trainers after the web listener opens.
            if failure.code != 503 or time.monotonic() >= deadline:
                raise
            failure.close()
            time.sleep(2)


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


def start_server(server, output):
    return subprocess.Popen(
        ["java", "-Xmx3G", "-XX:ActiveProcessorCount=2", "-jar", "fabric-server.jar", "nogui"],
        cwd=server, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
    )


def api(opener, path, **params):
    with opener.open(PANEL_ADDRESS + "/api/pc/" + path + "?" + urllib.parse.urlencode(params), timeout=10) as response:
        return json.load(response)


def panel_request(opener, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    headers = {} if payload is None else {"Content-Type": "application/json", "Origin": PANEL_ADDRESS}
    request = urllib.request.Request(PANEL_ADDRESS + path, data=data, headers=headers)
    with opener.open(request, timeout=10) as response:
        return json.load(response)


def wait_for_job(opener, action, terminal, seconds=240):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        job = panel_request(opener, "/api/job").get("job")
        if job is None or job.get("action") != action:
            raise RuntimeError(f"Panel lost the {action} operation")
        if job.get("status") != "running":
            if job["status"] not in terminal:
                raise RuntimeError(f"Panel {action} failed: {job.get('message')}")
            return job
        time.sleep(1)
    raise RuntimeError(f"Panel {action} did not finish within {seconds} seconds")


def assert_live_settings(live, config):
    for field in ("manageExperience", "globalXpMultiplier", "playerXpMultipliers", "groupXpMultipliers",
                  "playerXpGroups", "levelCapMode", "globalLevelCap", "wildLevelBehavior", "captureMultiplier"):
        if live.get(field) != config.get(field):
            raise RuntimeError(f"Runtime gameplay setting {field} does not match the expected config")


def has_fixture_pokemon(box):
    return any(slot["species"] == "cobblemon:pikachu" and slot["level"] == 13 and slot["shiny"]
               for slot in box["slots"])


def live_pc_smoke(opener, dedicated):
    try:
        with urllib.request.urlopen(PANEL_ADDRESS + "/api/pc/players", timeout=8):
            raise RuntimeError("PC endpoint allowed an unauthenticated request")
    except urllib.error.HTTPError as failure:
        if failure.code != 401:
            raise
        failure.close()
    matches = api(opener, "players", q=PLAYER)["players"]
    (profile,) = [entry for entry in matches if entry["name"] == PLAYER and entry["online"]]
    for properties in ["bulbasaur"] * 6 + ["pikachu level=13 shiny=true"]:
        dedicated.stdin.write(f"givepokemonother {PLAYER} {properties}\n".encode())
    dedicated.stdin.flush()
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        box = api(opener, "box", player=profile["uuid"], box=0)
        if box["source"] == "live" and has_fixture_pokemon(box):
            print("Authenticated PC lookup found the shiny level-13 Pikachu in a live box.")
            return profile["uuid"]
        time.sleep(1)
    raise RuntimeError("The fixture Pokémon did not appear in the live box")


def validation_smoke(opener, dedicated):
    # Keep one loaded PC large enough for a deterministic store mutation while it is scanned.
    dedicated.stdin.write((f"givepokemonother {PLAYER} bulbasaur\n" * 240).encode())
    dedicated.stdin.flush()
    time.sleep(1)
    with opener.open(PANEL_ADDRESS + "/api/state", timeout=8) as response:
        state = json.load(response)
    candidate = state["active"]
    candidate["restrictExistingPokemon"] = not candidate["restrictExistingPokemon"]
    payload = {
        "action": "validate",
        "expectedRevision": state["editRevision"],
        "candidate": candidate,
        "fingerprint": None,
    }
    request = urllib.request.Request(
        PANEL_ADDRESS + "/api/action", json.dumps(payload).encode(),
        {"Content-Type": "application/json", "Origin": PANEL_ADDRESS},
    )
    with opener.open(request, timeout=8) as response:
        if json.load(response)["job"]["status"] not in {"running", "validated"}:
            raise RuntimeError("Panel did not start the player-storage validation")

    deadline = time.monotonic() + 240
    mutation_sent = False
    store_recheck_seen = False
    while time.monotonic() < deadline:
        with opener.open(PANEL_ADDRESS + "/api/job", timeout=8) as response:
            job = json.load(response)["job"]
        if job is None:
            raise RuntimeError("Panel lost the player-storage validation job")
        phase = (job.get("progress") or {}).get("phase")
        if phase == "player-scan" and not mutation_sent:
            dedicated.stdin.write(f"givepokemonother {PLAYER} pikachu level=14\n".encode())
            dedicated.stdin.flush()
            mutation_sent = True
        if phase == "player-store-recheck":
            store_recheck_seen = True
        if job["status"] != "running":
            if job["status"] != "validated":
                raise RuntimeError(f"Player-storage validation failed: {job['message']}")
            report = job["report"]
            if report["inspectedPlayers"] < 1:
                raise RuntimeError("Validation completed without scanning the connected player's storage")
            if not mutation_sent or not store_recheck_seen:
                raise RuntimeError("Validation did not re-evaluate the loaded store mutated during its scan")
            print(
                "Panel validation scanned loaded storage in bounded batches and re-evaluated only "
                f"the changed store ({report['inspectedPlayers']} player(s))."
            )
            return
        time.sleep(0.1)
    raise RuntimeError("Player-storage validation did not finish within 240 seconds")


def live_settings_smoke(opener):
    state = panel_request(opener, "/api/state")
    if "liveGameplaySettings" not in state:
        print("Live gameplay settings are not exposed by this older build; selective-apply smoke skipped.")
        return
    original = state["active"]
    candidate = json.loads(json.dumps(original))
    candidate["manageExperience"] = True
    candidate["globalXpMultiplier"] = 1.5 if original["globalXpMultiplier"] != 1.5 else 1.25
    candidate["levelCapMode"] = "global"
    candidate["globalLevelCap"] = 37 if original["globalLevelCap"] != 37 else 38
    candidate["wildLevelBehavior"] = "block" if original["wildLevelBehavior"] != "block" else "clamp"
    candidate["captureMultiplier"] = 1.5 if original["captureMultiplier"] != 1.5 else 1.25
    validation = panel_request(opener, "/api/action", {
        "action": "validate", "expectedRevision": state["editRevision"],
        "candidate": candidate, "fingerprint": None,
    }).get("job")
    if validation is None or validation.get("status") not in {"running", "validated"}:
        raise RuntimeError("Panel did not start gameplay-settings validation")
    if validation["status"] == "running":
        validation = wait_for_job(opener, "validate", {"validated"})
    save = panel_request(opener, "/api/action", {
        "action": "save", "expectedRevision": state["editRevision"],
        "candidate": candidate, "fingerprint": validation["fingerprint"],
    }).get("job")
    if save is None or save.get("action") != "save":
        raise RuntimeError("Panel did not start saving gameplay settings")
    if save["status"] == "running":
        save = wait_for_job(opener, "save", {"saved"})
    pending_state = save.get("state")
    if pending_state is None or not pending_state.get("pendingGameplaySettingsDiffer"):
        raise RuntimeError("Saved gameplay settings were not marked as pending live application")

    applied = panel_request(opener, "/api/action", {
        "action": "applyLiveSettings", "expectedRevision": pending_state["editRevision"],
    })
    if applied.get("pendingGameplaySettingsDiffer"):
        raise RuntimeError("Panel still reports gameplay settings as unapplied")
    assert_live_settings(applied["liveGameplaySettings"], candidate)
    if applied["active"]["generations"] != original["generations"]:
        raise RuntimeError("Applying live gameplay settings changed active generation rules")

    discard = panel_request(opener, "/api/action", {
        "action": "discard", "expectedRevision": applied["editRevision"],
    }).get("job")
    if discard is None or discard.get("action") != "discard":
        raise RuntimeError("Panel did not start discarding the pending revision")
    if discard["status"] == "running":
        discard = wait_for_job(opener, "discard", {"completed"})
    restored = discard.get("state")
    if restored is None or restored.get("pending") is not None:
        raise RuntimeError("Discard did not remove the pending revision")
    assert_live_settings(restored["liveGameplaySettings"], restored["active"])
    print("XP, level caps, and capture applied live; discarding restored active gameplay settings.")


def saved_pc_smoke(root, server, player_id):
    server_log = root / "server-offline-pc.log"
    with server_log.open("wb") as output:
        dedicated = start_server(server, output)
        try:
            wait_for(dedicated, server_log, "Painel web local:", 300)
            opener = panel_smoke(server)
            matches = api(opener, "players", q=player_id)["players"]
            if not any(entry["uuid"] == player_id and not entry["online"] for entry in matches):
                raise RuntimeError("The saved player was not discoverable offline")
            box = api(opener, "box", player=player_id, box=0)
            if box["source"] != "saved" or not has_fixture_pokemon(box):
                raise RuntimeError("The saved PC did not contain the fixture Pokémon")
            print("Read-only offline lookup found the same Pokémon after restart.")
        finally:
            finish(dedicated, stop=True)


def run(root):
    server, client = root / "server", root / "client"
    server.mkdir()
    client.mkdir()
    install_pack(root, server, client)
    (manager,) = (root / "release").glob("*.jar")
    with zipfile.ZipFile(manager) as mod:
        version = tuple(map(int, json.loads(mod.read("fabric.mod.json"))["version"].split(".")))
    shutil.copy2(manager, server / "mods" / manager.name)
    for jar in (client / "mods").glob("*.jar"):
        with zipfile.ZipFile(jar) as mod:
            # Some Fabric mods use lenient JSON. Inspect only the identity marker.
            if "fabric.mod.json" in mod.namelist() and re.search(
                rb'"id"\s*:\s*"generation_control"', mod.read("fabric.mod.json")
            ):
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
    checked_pc = None
    with server_log.open("wb") as output:
        dedicated = start_server(server, output)
        try:
            wait_for(dedicated, server_log, "Painel web local:", 300)
            opener = panel_smoke(server)
            with (root / "client.log").open("wb") as output:
                player = subprocess.Popen(args, cwd=client, stdout=output, stderr=subprocess.STDOUT)
                try:
                    wait_for(player, server_log, PLAYER + " joined the game", 330)
                    print("Fabric client without the manager joined the dedicated server.")
                    if version >= (0, 4, 8):
                        checked_pc = live_pc_smoke(opener, dedicated)
                        validation_smoke(opener, dedicated)
                        live_settings_smoke(opener)
                finally:
                    finish(player)
            if checked_pc:
                wait_for(dedicated, server_log, PLAYER + " left the game", 30)
        finally:
            finish(dedicated, stop=True)
    if checked_pc:
        saved_pc_smoke(root, server, checked_pc)


if __name__ == "__main__":
    run(Path(sys.argv[1]).resolve())
