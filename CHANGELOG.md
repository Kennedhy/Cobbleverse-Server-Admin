# Changelog

Notable changes for each release are recorded here. Versions follow `MAJOR.MINOR.PATCH` and are tagged as `vX.Y.Z`.

## [0.8.0] — 2026-11-19 — Preview

- Target the Cobbleverse 1.8.0 stack: Cobblemon 1.8.4, Fabric Loader 0.19.5, RCT 0.19.2-beta, and RCT API 0.16.2-beta.
- Preserve the new starter-category order and random-starter setting when generation rules filter starter choices.
- Remove the Fight or Flight settings page and API route because Cobbleverse 1.8.0 removes that mod; its aggression controls are not represented as supported addon features.
- Filter nested Alpha herd members and habitat-pool spawn records, including species forms and modifiers.
- Adapt Raid Dens policy checks to its asynchronous boss cache, applying forbidden species as zero-weight datapack resources without synchronously resolving every boss during a roll.
- Bind the web panel on `0.0.0.0`, require an explicit HTTP origin for direct network access, and use port 18765 by default; keep credentials protected by origin checks and document private-network-only HTTP.
- Show Cobblemon Pokémon items and fuller hover details in the market and PC selection screens, including nature, ability, size, IVs, EVs, and moves, without adding client mods or redistributing Pokémon sprites.
- Fix a Gradle build-ordering defect where the sources archive read the generated web panel without declaring the dependency, so a full `gradlew build` failed when the web output was missing; the sources archive also no longer packages the generated panel.

## [0.7.0] — 2026-11-14 — Preview

- Add an additional legendary capture multiplier and editable per-species/form caps shared by the entire world; count player parties, offline PCs, and Pokémon held in GTS escrow.
- Build the legendary population index once per world startup in bounded batches, then keep it current from Cobblemon gain, release, evolution, capture, PC-admin, and GTS events without recurring PC scans.
- Add a searchable generation-grouped legendary table to the web panel with current world counts, live-editable caps, and explicit unlimited values.
- Add a server-side GTS and auction house with a player inventory GUI, CobbleDollars pricing, Pokémon/held-item escrow, bids, refunds, and claimable deliveries.
- Add market administration in the private web panel for listings, completed sales, pending/recovering operations, moderation, and market limits.
- Persist market escrow and transaction journals in the world data directory, recover interrupted operations on login, and close expired listings from a single deadline check rather than periodic listing scans.
- Confirm Pokémon deposits and deliveries against Cobblemon's persisted NBT or JSON store files before completing escrow; keep them pending when disk state cannot be verified. Confirm item deposits and deliveries with persisted inventory markers, and verify online CobbleDollars balances in both the account JSON and playerdata NBT. Reject container items that still hold other items.
- Reconcile interrupted purchases and bids from matching persisted CobbleDollars account/playerdata balances on a single bounded worker, even while the buyer is offline; rotate checks so earlier ambiguous transactions cannot starve later ones. Keep ambiguous debits and item claims pending rather than risk a duplicate settlement or delivery.
- Keep panel GET routes read-only for listing expiry, expose completed settlement and pending-operation context in the market ledger, and show listing status and cancellation restrictions in the player GUI.
- Avoid JSON round-tripping the entire market journal for rollback, and tighten player listing pagination and stale-result handling in the web panel.
- Bundle SGui as a server-only dependency so players do not need to install this addon or an extra client mod.
- Index RCT player/trainer groups and difficulty profiles on configuration changes, cache filtered raid weight tables, avoid unnecessary Primal property allocations during battles, and use direct transaction lookup when cleaning player inventory markers.
- Retry transient busy responses a bounded number of times for read-only player PC queries, with cancellation when the user changes the selected player or page.
- Refresh verification checksums for Fabric API artifacts republished upstream and validate them against the matching packaged release.

## [0.6.0] — 2026-11-13 — Preview

- Allow administrators to add, edit, and delete Pokémon in player PCs from the web panel, including nickname, species/form, level, shiny, nature, ability, moves, IVs, and EVs.
- Keep viewer credentials read-only, confirm deletions, reject stale slot edits, and persist changes through Cobblemon's file-backed store API.
- Add configurable PvE level-cap rules: block the whole battle or only prevent an over-cap Pokémon from entering. PvP is always exempt from level-cap battle blocks.
- Add individual RCT progression management from the player PC page: select an allowed series and edit defeated trainers, with a saved-progress ledger, stale-data checks, and edit history. RCT progress edits require the player to be online and outside battle.
- Add grouped RCT trainer difficulty profiles for trainer categories/IDs and player UUID groups. Profiles adjust level, minimum IVs, and EVs on battle-only trainer Pokémon copies; PvP, wild Pokémon, and original datapack teams are untouched.
- Add a trainer management page for RCT spawn chance, intervals, limits, distances, and trainer-card/association rules. These controls use RCT's live configuration API.
- Add CobbleDollars NPC payout control and income multiplier. The multiplier is global and also affects wild Pokémon earnings; CobbleDollars does not expose per-trainer reward amounts.
- Add an optional shiny appearance-rate override using Cobblemon's global shiny setting and include it in live gameplay apply/discard.
- Keep trainer settings out of the main configuration draft: their pages save through authenticated admin routes, while viewer sessions remain read-only.

## [0.5.0] — 2026-11-08 — Preview

- Apply saved XP, wild-level, and capture settings selectively at runtime; keep generation, gym, datapack, and world policies pending until restart.
- Re-evaluate only changed loaded Pokémon stores during previews while preserving deduplication across party, PC, and reserve, with bounded temporary snapshots and a full-player fallback.
- Extend the pinned-pack runtime smoke to cover generation/XP/cap simulation, on-demand diagnostics, live gameplay apply/discard, and a PC store changing during validation.
- Show the actual runtime XP, level-cap, and capture settings in the overview and clarify which saved rules still require restart.
- Prevent XP group renames from overwriting an existing group and silently reassigning players.
- Include the Windows SSH tunnel helper with build artifacts and future release assets.
- Add a safe server-mod folder builder that preserves the client installation and omits explicit client-only JARs from the dedicated server copy.
- Clarify private HTTPS and SSH tunnel setup for administrators on dedicated hosts.

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
