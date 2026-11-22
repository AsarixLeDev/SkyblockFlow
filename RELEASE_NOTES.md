**SkyblockFlow PUBLIC-BETA-0.0.2** · Minecraft **26.2**, Fabric Loader **0.20.1**, Fabric API **0.161.0+26.2**, Java **25**.

Download `skyblockflow-PUBLIC-BETA-0.0.2.jar`, replace the previous mod JAR in your instance's `mods` folder, and restart Minecraft. Open settings with **F7** or `/skyblockflow`.

- **Excalia public data:** published job/fishing and island rankings refresh through the official public API. Players outside the published tops retain the assisted command lookup. Live XP sessions continue to use in-game confirmations.
- **Player-shop quotes:** item tooltips show the cheapest available seller and highest buyback offer, merchant names, unit prices and available stack totals. Sold-out and ambiguous listings are excluded.
- **Current server-shop prices:** dynamic public prices supplement direct shop lore and the bundled reference, with job bonuses applied where appropriate. Separate public-data toggles, caching and rate-limit handling are included.
- **Recipe materials:** scroll the checklist inside inventories and containers. Missing materials appear before fully possessed materials in every checklist.
- **Private messages:** the new **mp** chat tab follows the private-message alert pattern and includes your replies, without requiring the alarm to be enabled. Switching tabs preserves history and message compression.
- **Tracking and alerts:** improved recognition of Excalia's job icons, fishing XP and stylized skill-ready messages; more reliable mineral counts during mining skills; per-alert chat sounds and a picker limited to playable vanilla sounds.
- **Configuration migration:** a local import prompt can reuse an existing ExcaliaFlow configuration, with preferences reloaded together. Existing SkyblockFlow settings remain in `config/skyblockflow/`.

Public API results are snapshots: prices and stock can change, and shop coordinates are not provided. Settings for these requests are available under **Leaderboards → Public data settings** and **Shop values**.

Discord connection remains unavailable pending public-access verification. Shared edits and community names are opt-in.

The sources JAR is for developers. `SHA256SUMS` contains checksums for both JARs.
