# Development

## Requirements

- JDK 21
- Node.js 22.12 or newer and npm
- Git
- Network access to Fabric Maven, Maven Central, and Modrinth Maven for the first build
- The Gradle build is configured for a 1 GiB heap; this is development-machine memory, not server runtime overhead.

Gradle Wrapper pins Gradle 9.2.1 and verifies its distribution checksum. Fabric Loom and Modrinth artifact versions are pinned in `build.gradle`.

## Build

```sh
cd web
npm ci
cd ..
./gradlew --no-daemon --max-workers=1 remapJar
```

PowerShell:

```powershell
Push-Location web
npm ci
Pop-Location
.\gradlew.bat --no-daemon --max-workers=1 remapJar
```

The task builds the static React app and packages it into the remapped server-only mod. The distributable is `build/libs/cobbleverse-generation-control-<version>.jar`.

## Tests and local runtime

Run the unit suite with `./gradlew test`. To inspect a test server, use a separate copy of the modpack, world, and ports. Never point local validation at the original world. Runtime checks depend on the exact Cobblemon/RCT and addon versions; log those versions with any report.

The included development server tasks are not a substitute for the full Cobbleverse pack. A release must state which runtime checks were actually performed and leave unverified integrations as limitations.

## Contribution workflow

1. Open an issue for a bug or behavior change and describe the Minecraft, Fabric, Cobblemon, and RCT versions.
2. Make a focused branch and keep changes compatible with the server-only design.
3. Run `./gradlew remapJar`; run `./gradlew test` when changing code covered by the test suite.
4. Do not include game worlds, player data, server logs with secrets, tokens, or third-party mod JARs.
5. Update `CHANGELOG.md` and relevant docs for user-visible changes.

See [CONTRIBUTING.md](CONTRIBUTING.md) for review and licensing expectations.

## Release process

Group related changes into a release when they form a meaningful update. Keep incremental changes under `Unreleased` in the changelog and use the Build workflow's artifact for testing. The manual **Cobbleverse runtime smoke** workflow accepts a successful Build run ID from `main`.

Releases are created from version tags named `vX.Y.Z`. The GitHub Actions workflow builds the remapped JAR, calculates its SHA-256, and creates a GitHub Release. Before tagging:

- update `mod_version` in `gradle.properties` and the web package version;
- add release notes and known limitations to `CHANGELOG.md`;
- confirm the JAR is server-only and does not include third-party mod JARs;
- record the actual build and runtime verification in `docs/TESTING.md`.

Tags `v*` are treated as preview releases until production compatibility and representative load checks are complete.
