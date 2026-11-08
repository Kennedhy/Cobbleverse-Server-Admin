"""Check that the built manager JAR remains server-only and contains the web UI."""

import argparse
import json
from pathlib import Path
import zipfile


def verify(jar_path: Path):
    with zipfile.ZipFile(jar_path) as archive:
        names = set(archive.namelist())
        if "fabric.mod.json" not in names:
            raise ValueError("The JAR does not contain fabric.mod.json at its root.")
        metadata = json.loads(archive.read("fabric.mod.json"))

        if metadata.get("id") != "generation_control":
            raise ValueError("The JAR has an unexpected Fabric mod id.")
        if metadata.get("environment") != "server":
            raise ValueError("The manager must declare environment=server.")
        entrypoints = metadata.get("entrypoints")
        if (not isinstance(entrypoints, dict) or set(entrypoints) != {"server"}
                or not entrypoints.get("server")):
            raise ValueError("The manager must expose only its dedicated-server entrypoint.")

        mixins = metadata.get("mixins", [])
        mixin_configs = []
        for entry in mixins:
            if isinstance(entry, str):
                mixin_configs.append(entry)
            elif isinstance(entry, dict):
                if entry.get("environment") == "client":
                    raise ValueError("A client-only mixin configuration is present.")
                config = entry.get("config")
                if isinstance(config, str):
                    mixin_configs.append(config)
            else:
                raise ValueError("The JAR has an invalid Fabric mixin entry.")

        mixin_classes = 0
        for config in mixin_configs:
            if config not in names:
                raise ValueError(f"The declared mixin configuration is missing: {config}")
            config_data = json.loads(archive.read(config))
            declared = config_data.get("mixins", [])
            if any("client" in str(name).lower() for name in declared):
                raise ValueError(f"A client mixin is declared in {config}.")
            mixin_classes += len(declared)

        class_files = [name for name in names if name.endswith(".class")]
        if any("/client/" in f"/{name.lower()}" for name in class_files):
            raise ValueError("The JAR contains a class in a client package.")
        for name in class_files:
            if b"net/minecraft/client/" in archive.read(name):
                raise ValueError(f"A class references the Minecraft client API: {name}")

        if "web/index.html" not in names:
            raise ValueError("The bundled web panel index is missing.")
        assets = [name for name in names if name.startswith("web/assets/")]
        if not any(name.endswith(".js") for name in assets) or not any(name.endswith(".css") for name in assets):
            raise ValueError("The bundled web panel JavaScript or stylesheet is missing.")

    return len(class_files), mixin_classes, jar_path.stat().st_size


def main():
    parser = argparse.ArgumentParser(description="Valida o JAR server-only do gerenciador.")
    parser.add_argument("jar", type=Path, help="JAR remapeado produzido pelo Gradle")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error(f"JAR não encontrado: {args.jar}")
    try:
        classes, mixins, size = verify(args.jar)
    except (OSError, ValueError, KeyError, json.JSONDecodeError, zipfile.BadZipFile) as failure:
        parser.error(str(failure))
    print(f"Server-only JAR verified: {classes} classes, {mixins} mixins, {size:,} bytes; web assets present.")


if __name__ == "__main__":
    main()
