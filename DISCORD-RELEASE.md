# Discord release requirements

The publisher application ID is `1572690819566276729`. Only this public ID is bundled. The SkyblockFlow callback is `http://127.0.0.1:48533/callback`.

Public Client and the redirect have been configured. An owner-account sign-in and local read-only call check passed. **Public access for an ordinary non-team/non-tester account and native Windows behavior remain unverified.** This release ships with `discord_connection_enabled=false` and does not initiate Discord authentication or load saved Discord credentials. Keep both this flag and `discord_public_ready=false` until the following checks pass.

## Publisher checklist

- Confirm Discord has granted the application the RPC access needed for public users. Successful owner authorization or bot verification does not establish this.
- Test an account outside the application owner/team/tester list with `identify`, `rpc` and `rpc.voice.read`, on the SkyblockFlow callback.
- Check active call members, speaking/mute changes, token renewal, Discord restart, denied/cancelled login, disconnect and revocation.
- Test optional voice controls separately with `rpc.voice.write`. Confirm controls remain disabled until the player opts in.
- Test Windows named-pipe account validation and remembered-token ACLs on Windows; verify Unix socket peer checks on Linux.
- Keep the consent application name recognizable and publish accurate privacy/support information for the application.
- Set `discord_connection_enabled=true` and `discord_public_ready=true` only after these checks, then run `./gradlew publicRelease`.

## Implementation

OAuth uses fresh state, S256 PKCE, one-use callbacks, strict loopback host/path/method and issuer checks, and a five-minute login deadline. OAuth requests go directly to Discord over HTTPS, without redirects or unbounded response bodies. Credentials and provider error payloads are not logged.

The local RPC transport verifies the peer OS account. Remembered credentials are separate from exported profiles and restricted to the OS account; they are not encrypted. Disconnect prevents further overlay authentication while revocation is attempted. Pending removals cannot authenticate, and late sign-in/refresh responses cannot restore a disconnected account.

The initial mod connection is read-only. Optional microphone/deafen buttons are separately enabled and only issue local Discord voice-setting commands. The mod contains no call recording or audio upload. Players see a scrollable guide and trust notice before authorization.

Local checks exercise PKCE, callback validation/replay, private storage, token rotation, late responses, revocation and RPC fixtures. They do not replace the live public-account and Windows checks above.

References: [Discord OAuth2](https://docs.discord.com/developers/topics/oauth2), [Discord RPC](https://docs.discord.com/developers/topics/rpc), [desktop account linking](https://docs.discord.com/developers/discord-social-sdk/development-guides/account-linking-with-discord).
