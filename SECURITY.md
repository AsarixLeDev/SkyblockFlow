# Security

Report security concerns to **asarix on Discord**. Include the affected version, reproduction steps and likely impact. Do not post account tokens, private coordinates or other players' data in a public issue. Ordinary bugs can be reported in [GitHub Issues](https://github.com/AsarixLeDev/SkyblockFlow/issues).

SkyblockFlow does not download or execute code from edit invitations. Bundles use fixed allowed entries, bounded sizes, verified hashes and validated image/terrain dimensions. Invitations require local consent; HTTP transfers and identity checks use bounded requests. No audit can guarantee the absence of every defect: reports and reproducible fixes are welcome.

Community appearance documents are verified against a bundled Ed25519 public key. They select only bundled fonts and approved colors; arbitrary font URLs, actions, saturated reds and near-black colors are rejected, including unsafe colors between gradient stops. The private signing key is not distributed with the mod or deployed relay. A modified client can change its own display, but cannot publish a valid style to other clients without the signing key.

The relay uses account/IP/global request limits, bounded body sizes and concurrency, transfer deadlines and temporary-storage quotas. These controls reduce abuse; they do not guarantee availability during distributed attacks. Appearance authentication and edit-sharing authentication have separate permissions.

For source contributions, keep credentials, personal profiles, recordings and music out of Git. Preserve dependency licenses, opt-in disclosures and the public Discord release gate. Changes to relay retention must keep [PRIVACY.md](PRIVACY.md) and the in-game disclosure accurate.

Downloaded build dependencies have pinned checksums. Fabric Loom verifies the original Minecraft downloads and their signatures; its two generated Minecraft JARs are restricted to local repositories because their archive hashes vary between machines.
