# Web Client Architecture

Other Note Web is a static Kotlin/JS client for encrypted Nostr notes. It preserves the native app security model: signing, encryption, decryption, note reduction, and Markdown rendering happen in the browser or through a user-approved signer. There is no server-side note processing.

The web client supports:

- NIP-07 browser-extension sign-in.
- NIP-46 `bunker://` remote-signer sign-in, with new pairings session-only by default.
- Explicit opt-in remembered NIP-46 remote-signer reconnect under the generic storage key `on.web.nip46`.
- Session-only direct `nsec` sign-in, with default-off browser/password-manager form hints controlled by the user.
- Session-only generated identities with explicit `nsec` recovery acknowledgements.
- Encrypted kind `30078` note read/create/edit/delete when the active signer can encrypt, decrypt, and sign.
- In-memory note search and sort.
- Built-in themes, with only the generic selected theme ID persisted under `on.web.theme`.
- Active-account profile display with a safe thumbnail fallback.
- Session-only note relay settings, NIP-65 relay-list import/publish, relay migration, manual Sync/Migrate, and per-relay encrypted-event stats.
- Full-note-only Markdown rendering, safe links, Markdown images, and bare HTTPS image URLs with supported extensions.

The native clients do not bundle a custom app font. Android sets the app font family to the platform `sans` family, which maps to Roboto on Android, and the Compose desktop client uses Material/Compose default sans text. To make Other Note Web render closer to Android and Material defaults, the web client self-hosts Roboto WOFF2 files under `web/src/jsMain/resources/fonts/roboto/`. Those files are derived from Debian `fonts-roboto-unhinted` 2:0~20170802-4, whose upstream is <https://github.com/google/roboto>, and are licensed under Apache-2.0. The web app loads those font files from same-origin static resources only, with no Google Fonts, CDN fonts, remote font CSS, or external font hosts.

## Security Rules

- MUST keep Nostr signing client-side or signer-delegated.
- MUST keep note encryption client-side or signer-delegated.
- MUST keep note decryption client-side or signer-delegated.
- MUST NOT send plaintext notes to an Other Note server.
- MUST NOT send `nsec` values or private keys to an Other Note server.
- MUST NOT store direct `nsec` values, generated identity keys, or user private keys in `localStorage`, sessionStorage, IndexedDB, cookies, Cache Storage, or server sessions.
- MUST NOT log `nsec` values, private keys, bunker tokens, signer secrets, decrypted notes, decrypted payload JSON, full event JSON, raw decrypted NIP-44 payloads, or full sensitive URLs.
- MUST NOT add analytics or telemetry that can capture note text, keys, signer payloads, relay payloads, account secrets, or sensitive diagnostics.
- MUST keep NIP-46 signer transport relays separate from note fetch/publish relays.
- MUST keep direct `nsec` paste and generated web identities session-only from Other Note app storage.

## Sign-In

Recommended priority:

1. NIP-07 browser extension signer.
2. NIP-46 remote signer/bunker.
3. Direct `nsec` paste for the current browser session only.
4. Fresh generated identity for the current browser session only.

For NIP-07, the browser extension owns the private key and performs signing. Any NIP-44 encrypt/decrypt support uses the extension only if it exposes the needed APIs safely; otherwise encrypted note operations are unavailable through that signer.

For NIP-46, the remote signer owns the user's private key. Other Note uses a local NIP-46 communication key for encrypted kind `24133` request/response traffic. Web persistence of that communication key is default-off and allowed only when the user explicitly checks "Remember this remote signer on this browser." The remembered record is sensitive because it can request signer actions from the remote signer until revoked or forgotten, but it is not the user's private key and remains separate from note relays and note content. The remote signer may see plaintext note payloads during signer-mediated NIP-44 encrypt/decrypt operations by design.

For direct `nsec` fallback, the private key exists only in browser memory for the active session. It may be used to sign events and perform NIP-44 encrypt/decrypt locally, then is discarded on logout, refresh, tab close, or process end as far as browser behavior allows. Users may explicitly opt in to form/autocomplete semantics that let a compatible browser or password manager offer to save/fill the field. That storage is user-controlled external browser/password-manager storage, not Other Note app-controlled storage.

Generated identities are a separate deliberate flow. The generated `nsec` is displayed only until the user cancels or acknowledges recovery risk and uses it for the current session. Other Note Web cannot recover the key after the generated-key state is cleared.

## Browser Storage

The web client persists only two records:

- `on.web.theme`: a generic selected theme ID.
- `on.web.nip46`: an explicit opt-in remembered NIP-46 communication-session record.

The remembered NIP-46 record may store only version, returned user pubkey, local NIP-46 communication private key, communication pubkey, remote signer pubkey, signer transport relay URLs, and timestamps. It must not store the original bunker token secret, user private key, direct `nsec`, generated identity key, note data, note relay settings, relay stats, profile data, search/sort state, or pending writes.

Everything else is memory-only:

- Active auth state.
- Direct-key and generated-identity sessions.
- Notes and decrypted payloads.
- Drafts and pending writes.
- Note relay choices and relay migration state.
- Relay stats.
- Profile metadata.
- Search and sort state.
- Rendered Markdown output.
- Link and image URLs.

Direct `nsec` fallback must not write the key to `localStorage`, sessionStorage, IndexedDB, cookies, Cache Storage, server sessions, analytics, crash reports, or logs.

## Notes And Relays

Other Note Web uses the same encrypted note event semantics as the native clients: kind `30078`, the `other-note:note:<note_id>` `d` tag, encrypted note payload schema, latest-event/tombstone materialization, and display-only Markdown rendering.

Note relay settings manage relays used to fetch and publish encrypted kind `30078` Other Note events. NIP-46 signer transport relays carry encrypted kind `24133` app/signer traffic and must remain separate from note relays. The note relay settings screen must not show, edit, import, or publish NIP-46 signer transport relays.

Web profile metadata reads may fetch the active account's public kind `0` profile event from the current note relays for identity display. The signed-in account header may render the active profile's supported HTTPS `picture` as a small thumbnail with a bundled placeholder fallback; remote `banner` URLs remain inert strings.

Full-note rendering may render the tested Markdown subset, linkify safe `http`/`https` URLs, and render supported HTTPS note-content image URLs only after the user opens the full-note view. Raw HTML is not rendered. Note cards and editors keep Markdown, URLs, and image references inert/raw and must not prefetch remote images.

## Deployment

Use static hosting with no server-side note processing.

Deployment requirements:

- HTTPS only.
- Strong Content Security Policy.
- No third-party analytics.
- No third-party script injection.
- No third-party font hosts.
- No server-side signing, encryption, decryption, or plaintext note processing.
- No server logs containing note text, `nsec` values, private keys, bunker tokens, signer payloads, or sensitive relay payloads.
- Immutable/static assets where practical.
- No service worker or offline cache without a separate reviewed cache/security design.

Served JavaScript has a larger trust surface than native binaries because the code can change at deploy time. Any public web deployment must treat build provenance, asset integrity, CSP, and dependency review as release blockers. The concrete static hosting header template and web smoke checklist live in [web-deployment-security.md](web-deployment-security.md).

## Shared And Web-Specific Code

Shared common code covers note models, encrypted payload schema, Nostr event helpers, relay URL validation, note reducer behavior, Markdown parsing, search/sort helpers, theme definitions, profile metadata parsing, and protocol-level NIP-46 helpers where browser-compatible.

Web-specific code covers the NIP-07 signer adapter, browser WebSocket relay client, browser storage adapter for the two allowed records, browser platform service factory, browser clipboard/link handling, browser-safe diagnostics/error copy, static hosting, CSP, and deployment configuration.

## Limitations

- No durable web sessions except explicit opt-in remembered NIP-46 remote-signer communication sessions.
- Direct `nsec` web flow is session-only from Other Note app storage.
- Generated identities are session-only from Other Note app storage after the acknowledgement flow clears the generated key.
- No service worker or offline mode.
- No durable web note cache.
- No persistent pending-write queue.
- No durable web relay preferences.
- No durable web search/sort preferences.
- Web relay migration and manual Sync/Migrate are session-only and best-effort; there is no durable migration queue.
- User-selected relay URLs require broad `wss:` connection allowance in CSP.
- Remote profile and note images may fetch from their source URL only when rendered.
