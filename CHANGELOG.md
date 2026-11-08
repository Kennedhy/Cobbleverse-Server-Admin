# Changelog

Notable changes for each release are recorded here. Versions follow `MAJOR.MINOR.PATCH` and are tagged as `vX.Y.Z`.

## [Unreleased]

- Prevent XP group renames from overwriting an existing group and silently reassigning players.

## [0.4.8] — Preview

- Added a read-only player PC page with player search, box navigation, species, form, level, and shiny status.
- Inspect at most one live box on the game thread or read one saved NBT/JSON store on an HTTP worker when requested.

## [0.4.7] — Preview

- Limit queued web requests to the game thread and discard requests that cannot start in three seconds.
- Retry validation status reads when the game thread reports that it is busy.

## [0.4.6] — Preview

- Limited raid, trainer and gym resource discovery to the namespaces used by the policy overlay.
- Skip gym function and structure discovery when the gym filter is disabled.
- Reduce form name allocations during Pokémon policy checks and build filtered function text in one pass.

## [0.4.5] — Preview

- Added optional private HTTPS access through Tailscale Serve or a reverse proxy on the Minecraft host.
- Added Secure session cookies for the configured HTTPS origin and origin checks for logout.
- Reduced RCT cap lookups during wild Pokémon spawns when the encounter already fits a nearby player's cap.

## [0.4.4] - 2026-11-07 — Preview

- Clarified the remote panel login flow and SSH tunnel setup.
- Bound the Windows SSH helper's local port explicitly to loopback and rejected unsafe host arguments.
- Made source builds independent of a local modpack by resolving pinned dependencies through Modrinth Maven.
- Limited compile-time Fabric API dependencies to modules used by the addon and kept GeckoLib as a non-remapped compile-only dependency.
- Added open-source project, security, contribution, build, and release documentation.

This is a preview release. It has not yet been validated on a public or production server. See [validation status](docs/TESTING.md).

## [0.4.3] - 2026-11-07 — Previous preview

- Server-only Fabric addon with a private web panel for generations, gyms, XP, level caps, capture, validation, and diagnostics.
- Added SSH tunnel helper and loopback-only panel access.
- Tested in an isolated local copy of the modpack; see [validation status](docs/TESTING.md) for the scope and limits of that test.
