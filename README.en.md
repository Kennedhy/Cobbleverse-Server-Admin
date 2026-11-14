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
- Global and legendary-only capture multipliers that preserve Poké Ball effects; a shared per-world cap for each species/form; and an optional shiny appearance rate.
- RCT trainer spawn controls for chance, interval, caps, distance, trainer-card requirements, and the association NPC.
- RCT trainer battle difficulty profiles by trainer and player groups; level, IV, and EV changes affect battle copies only.
- CobbleDollars NPC payouts and a global income multiplier. The multiplier also affects wild Pokémon earnings; the mod does not expose per-trainer payout values.
- Paginated player PC inspection, including nature, ability, moves, IVs, and EVs; administrators can add, edit, and delete Pokémon. They can also edit an online player's RCT series and defeated trainers.
- Online player dashboard with direct PC access and supervised server restart.
- On-demand validation, configuration revision history, draft discard, and previous-revision restore.
- Individual administrator and viewer credentials, immediate session revocation, and per-account revision authorship.
- CobbleDollars GTS and auctions through a player GUI, with PC Pokémon and held-item listings, bids, refunds, and claimable deliveries.
- Web administration for market listings, completed sales, pending/recovering operations, moderation, and server limits.

Generation, gym, XP, level, capture, shiny, and legendary-cap settings use the panel's revision, validation, and save flow. XP, levels, capture, shiny, and legendary caps can be applied live; generations, gyms, datapacks, and world rules require a restart. Legendary counts are shared by every player in the same world and include parties, offline PCs, and GTS escrow; unowned wild Pokémon are not included. Trainer spawn and economy settings use the RCT/CobbleDollars APIs, and difficulty profiles apply to upcoming battles. RCT progress can only be edited while the player is online and outside battle. Existing Pokémon and structures are not removed.

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
| CobbleDollars (GTS) | 2.0.0 Beta 5.1 |
| SGui (bundled in JAR) | 1.6.1+1.21.1 |

Raid, mega-evolution, and other spawn integrations depend on the versions in the target modpack. See [known limitations and pending checks](docs/TESTING.md).

The market requires CobbleDollars 2.0.0 Beta 5.1. Its player interface uses SGui bundled as a nested server-side dependency; players do not need this addon or an extra client mod.

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

The addon does not continuously scan players, Pokémon, entities, or chunks. Heavy validation starts only when an administrator requests it and is processed in batches. No source from another mod is copied into this project; the SGui server GUI library is bundled separately with its LGPL license. **There is no A/B benchmark proving TPS or memory improvements yet**; see [architecture and cost](docs/ARCHITECTURE.md).

## License

Project code is licensed under [MIT](LICENSE). The JAR bundles SGui 1.6.1+1.21.1 as a nested library; SGui remains under LGPL-3.0 and its license is included in the JAR and source tree at [licenses/SGUI-LGPL-3.0.txt](licenses/SGUI-LGPL-3.0.txt). See [third-party notices](THIRD_PARTY_NOTICES.md). Cobblemon and RCT remain server dependencies and are not embedded in this artifact.
