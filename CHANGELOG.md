# Changelog

Notable changes for each release are recorded here. Versions follow `MAJOR.MINOR.PATCH` and are tagged as `vX.Y.Z`.

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
