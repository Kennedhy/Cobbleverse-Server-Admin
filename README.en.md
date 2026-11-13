# Cobbleverse Server Admin

Fabric **server-only** addon for managing Cobbleverse rules through a private web panel. Players do not need this JAR on their clients.

[Leia em português](README.md)

> **Version 0.6.0 — preview.** First use it on a copy of the server and world. See the [validation status](docs/TESTING.md).

## Features

- Pokémon generations: one generation, up to a generation, or a custom selection, with species and form exceptions.
- Gym circuits and structure rules recognized by supported integrations.
- Global XP multiplier, panel-managed groups, and per-player adjustments.
- Wild Pokémon level caps, global or derived from RCT progress. When multiple players are nearby, the highest applicable cap is used.
- Optional PvE cap rules: block the whole battle or prevent only the over-cap Pokémon from entering; PvP is exempt from level-cap battle blocks.
- Global capture multiplier that preserves Poké Ball effects, plus an optional shiny appearance rate.
- RCT trainer spawn controls for chance, interval, caps, distance, trainer-card requirements, and the association NPC.
- RCT trainer battle difficulty profiles by trainer and player groups; level, IV, and EV changes affect battle copies only.
- CobbleDollars NPC payouts and a global income multiplier. The multiplier also affects wild Pokémon earnings; the mod does not expose per-trainer payout values.
- Paginated player PC inspection, including nature, ability, moves, IVs, and EVs; administrators can add, edit, and delete Pokémon. They can also edit an online player's RCT series and defeated trainers.
- Online player dashboard with direct PC access and supervised server restart.
- On-demand validation, configuration revision history, draft discard, and previous-revision restore.
- Individual administrator and viewer credentials, immediate session revocation, and per-account revision authorship.

Generation, gym, XP, level, capture, and shiny settings use the panel's revision, validation, and save flow. XP, levels, capture, and shiny can be applied live; generations, gyms, datapacks, and world rules require a restart. Trainer spawn and economy settings use the RCT/CobbleDollars APIs, and difficulty profiles apply to upcoming battles. RCT progress can only be edited while the player is online and outside battle. Existing Pokémon and structures are not removed.

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
2. Download the JAR and the `open-admin-tunnel.ps1` and `prepare_server_mods.py` tools from [Releases](https://github.com/Kennedhy/Cobbleverse-Server-Admin/releases). Older releases may not include these files; download them from the matching source archive instead.
3. Put the JAR in the Fabric server's `mods/` folder only. Do not install it on clients.
4. Start the server and run `/geracoes web` as an administrator to get the panel address and paths for the initial shared credentials.
5. Open the panel through private HTTPS or an SSH tunnel and use the initial admin token. Create individual tokens from the server console. Viewers can inspect players, PCs, and history, and use the simulator. [Panel administration](docs/ADMIN.md)

The panel listens on `127.0.0.1:1` by default (`port=0001`). Browsers block direct HTTP access to port 1. To change the port, edit `<world>/generation-control/web.properties` and restart the server.

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
