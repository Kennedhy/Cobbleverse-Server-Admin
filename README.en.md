# Cobbleverse Server Admin

Fabric **server-only** addon for managing Cobbleverse rules through a private web panel. Players do not need this JAR on their clients.

[Leia em português](README.md)

> **Version 0.5.0 — preview.** First use it on a copy of the server and world. See the [validation status](docs/TESTING.md).

## Features

- Pokémon generations: one generation, up to a generation, or a custom selection, with species and form exceptions.
- Gym circuits and structure rules recognized by supported integrations.
- Global XP multiplier, panel-managed groups, and per-player adjustments.
- Wild Pokémon level caps, global or derived from RCT progress. When multiple players are nearby, the highest applicable cap is used.
- Global capture multiplier that preserves Poké Ball effects.
- Read-only, paginated player PC inspection, including nature, ability, moves, IVs, and EVs.
- Quick and world-named presets, plus JSON import/export with a comparison before applying to the draft.
- Validation, on-demand MSPT, estimated TPS, Java process CPU and GC-time history, configuration revision history, draft discard, and previous-revision restore.
- Individual administrator and viewer credentials, immediate session revocation, and per-account revision authorship.

The panel saves a pending revision. After validating and saving, an administrator can apply XP, level caps, and capture settings live; generations, gyms, datapacks, and world rules take effect after a server restart. Existing Pokémon and structures are not removed.

## Compatibility

| Component | Target |
| --- | --- |
| Minecraft | 1.21.1 |
| Java | 21+ |
| Fabric Loader | 0.18.4+ |
| Fabric API | 0.116.14+1.21.1 |
| Cobblemon | 1.7.3 |
| Radical Cobblemon Trainers | 0.18.1-beta |
| RCT API | 0.15.2-beta |

Raid, mega-evolution, and other spawn integrations depend on the versions in the target modpack. See [known limitations and pending checks](docs/TESTING.md).

## Server installation

1. Back up the server and world, then validate the addon on a copy.
2. Download the JAR from [Releases](https://github.com/Kennedhy/Cobbleverse-Server-Admin/releases). Future releases will also provide `open-admin-tunnel.ps1` and `prepare_server_mods.py` as separate tools. If an older release lacks them, download that version's source archive.
3. Put the JAR in the Fabric server's `mods/` folder only. Do not install it on clients.
4. Start the server and run `/geracoes web` as an administrator to get the panel address and paths for the initial shared credentials.
5. On the server machine, open `http://127.0.0.1:8765` and use the initial admin token. In **Access**, create an individual token for each person and revoke shared credentials when they are no longer needed. Viewers can inspect status, PCs, history, export configurations, and use the simulator; only administrators can edit rules or manage access. For remote access, configure private HTTPS or an SSH tunnel. See [Panel administration](docs/ADMIN.md).

The panel binds to `127.0.0.1`. **Do not expose port 8765 to the internet or players.** Port settings are in `<world>/generation-control/web.properties`; changes require a server restart.

## Build from source

Requirements: Java 21, Node.js 22.12+, and npm. Gradle uses 1 GiB of heap during the build only. Mod dependencies are resolved from immutable versions on Modrinth's official Maven repository, so a local Cobbleverse installation is not needed.

```sh
cd web && npm ci && cd ..
./gradlew --no-daemon --max-workers=1 remapJar
```

The remapped JAR is written to `build/libs/`. React, Vite, and Node generate the static web assets at build time. See [development](DEVELOPMENT.md) for builds, tests, and releases.

## Security and performance

The JDK HTTP server uses per-account tokens, sessions, origin validation, and no CORS. Credentials are stored with world data; the mod attempts to restrict file and directory permissions to the server account. Panel revisions identify the account in history. The Windows helper opens an SSH tunnel; `prepare_server_mods.py` builds a server mod folder without changing the client installation. See [administration](docs/ADMIN.md).

The addon does not continuously scan players, Pokémon, entities, or chunks. Heavy validation starts only when an administrator requests it and is processed in batches. No third-party mod code or duplicate optimization layer is bundled. **There is no A/B benchmark proving TPS or memory improvements yet**; see [architecture and cost](docs/ARCHITECTURE.md).

## License

Project code is licensed under [MIT](LICENSE). The JAR does not redistribute Cobblemon, RCT, or other third-party mods; each dependency remains under its author's terms and license.
