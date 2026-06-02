# Other Note Key Management

This document is durable project policy for handling Nostr private keys and signer access in Other Note.

## Core Invariant

- `nsec` values and private keys must never be stored in plaintext.
- `nsec` values and private keys must never be logged.
- `nsec` values and private keys must never be sent to an Other Note server.
- `nsec` values and private keys must never be sent to Nostr relays.
- Decrypted note bodies, decrypted payload JSON, and NIP-44 plaintext must remain local to the user device.
- If secure storage is unavailable, the app must use session-only mode.

Unavailable/no-op storage is intentional. It is safer to disable saved-key mode than to add a fake secure storage fallback.

## Preferred Key And Signing Hierarchy

Use this priority order:

1. External signer or delegated signer where available.
2. NIP-46 remote signer/bunker where available and appropriate.
3. Session-only pasted `nsec`.
4. Fresh generated `nsec` only through the deliberate "Create new identity" flow.
5. Explicit saved-device `nsec` only through OS-backed credential storage.
6. No plaintext persistence fallback.

Session-only `nsec` means the key is held only in process memory for the active app session. The input field must be cleared after login and the full `nsec` must not be displayed after entry.

Other Note may generate a fresh Nostr identity for a user-facing "Create new identity" flow. The generated `nsec` is the private key. It may be displayed only inside the deliberate generation flow so the user can save it outside Other Note, save it explicitly to the Linux desktop keyring where supported, or import it into a signer, and it must never be written to app preferences, DataStore, SQLite, files, JSON stores, durable relay caches, logs, analytics, crash reports, or relay events. The generated direct-key session remains session-only unless the desktop user explicitly saves the key to the OS keyring. When production crypto and a relay client are available, that session may use the in-memory private key for NIP-44 v2 encryption/decryption and NIP-01 signing, but durable storage must still contain only signed encrypted events and safe relay metadata. Losing the generated `nsec` means losing access to encrypted notes for that identity if no signer/keyring/password-manager copy exists.

Generated-identity UX must require explicit acknowledgement that the user saved the `nsec` and understands the recovery risk before using it for a session. Clipboard copy must never happen automatically. If a future platform-specific copy action is added, it must be explicit, warning-labeled, and clear the clipboard after a short delay where the platform API makes that reliable.

Android sign-in UX should make the NIP-55 signer path the most prominent recommended option when a signer is detected, hide that option when no signer is detected, show NIP-46 remote signer/bunker as an advanced secondary option when available, keep pasted `nsec` lower emphasis and session-only, and present fresh identity generation as a text-only deliberate action.

## Android Plan

Preferred Android signing is external signer delegation through NIP-55 Android signer apps such as Amber. NIP-46 remote signer/bunker support is available as a signer-mediated foundation path; its local communication key material may be saved as a reusable remote-signer session but is never the user's private key.

Current Android NIP-55 status is discovery, explicit public-key request, internal signer primitive coverage, and relay-backed signer note creation/edit/delete/recovery:

- Other Note uses generic NIP-55 `nostrsigner:` intent discovery to determine whether a signer app is installed.
- Amber is the primary planned/tested target, but the architecture does not hard-require Amber.
- The login UI can show signer availability and a "Use Android signer" action.
- The action launches a user-triggered NIP-55 `get_public_key` intent and stores only public identity metadata in memory for the active app session: public key hex, `npub`, and signer package when returned.
- Internal tests and helper code cover NIP-55 `SIGN_EVENT`, NIP-44 encrypt/decrypt, and signer-backed note-event construction. These development test actions are no longer exposed in the normal app UI.
- Normal editor save/edit in an Android signer session can build a signed encrypted kind `30078` event through the same signer pipeline, publish it to configured relays, and display it after at least one relay accepts the write. Delete creates a signer-backed tombstone event with the same d tag, publishes it, and hides the note after at least one relay accepts.
- Android signer login can fetch kind `30078` Other Note events for the signer pubkey from relays, validate id/signature before decryption, decrypt through signer NIP-44, decode `NotePayload`, reduce replacements/tombstones, and update visible notes incrementally.
- Android signer relay cache and pending writes are durable in app-private no-backup storage. They are not key storage: they store signed encrypted Nostr events and safe relay metadata only, never `nsec`, private keys, decrypted notes, decrypted payload JSON, or NIP-44 plaintext.
- Android manifest backup and data-extraction rules must exclude all app data from cloud backup and device-transfer extraction. This protects encrypted caches, pending writes, relay settings, signer metadata, and any future key material from Android backup surfaces; it must not be treated as a backup mechanism.
- The Android encrypted event cache is used after signer login for the same public key so cached notes can recover after relaunch before or alongside relay fetch. The pending-write queue retries unfinished fanout on the next signer login or sync by republishing already signed encrypted events, without recreating raw signer requests or storing signer secrets.
- The initial `get_public_key` request asks for optional `sign_event` permissions for kind `1`, encrypted note kind `30078`, and relay-list kind `10002`, plus `nip44_encrypt` and `nip44_decrypt` permissions so compatible signers can approve the ContentResolver requests.
- Signer-built events must be validated locally before success is reported: signer pubkey must match the session, event fields must match the request, and the NIP-01 event id/signature must verify.
- The scaffold must not store, log, or transmit `nsec` values or private keys.

Direct `nsec` paste may exist as a fallback. The direct `nsec` field should use password/autofill/credential behavior so Android and Google Password Manager can prompt where appropriate. The app itself must not save Android `nsec` values to plaintext app storage. If production crypto is available, direct session-only keys can encrypt/decrypt and sign notes in memory for relay publication and recovery; if production crypto is unavailable, the safety fallback remains local-only and must not emit plaintext.

The "Create new identity" flow can be used to generate an `nsec`, but the recommended Android path remains importing or saving that key in Amber or another NIP-55 signer and then using Android signer login in Other Note. Users who do not want to manage a raw `nsec` should prefer Android signer, NIP-46 remote signer/bunker, or future OS-backed credential storage.

Saved-device `nsec` support on Android must require Android secure credential or keystore-backed storage when implemented. Until that exists, Android saved-key mode stays disabled and session-only or signer-mediated modes are used.

## Local App Relay Settings

Local relay settings are app/note relay settings only. They control the WebSocket relays used to fetch and publish encrypted kind `30078` note events. They must not modify NIP-46 signer-transport relays, which are sourced from bunker tokens and signer transport state for encrypted kind `24133` app/signer request traffic.

Relay settings may persist only normalized relay URLs and safe metadata. They must never store `nsec`, private keys, generated key material, NIP-46 token secrets, decrypted note bodies, decrypted payload JSON, or NIP-44 plaintext. Android stores the local app relay list in app-private no-backup storage; desktop stores it under the local app data directory.

Relay URL validation should accept production `wss://` relay URLs and naked relay hostnames that can be safely normalized to `wss://`. It must reject malformed URLs, `http://`, `https://`, query strings, fragments, whitespace, and duplicates after normalization. `ws://` may be accepted only for local development hosts. Removing all app relays is blocked in the current UI because relay sync and publishing require at least one app relay. Users can explicitly restore the default app relay set.

Relay testing applies only to local app/note relays. A direct/session-only key session may publish and fetch a harmless non-note test event to verify write and read capability. Signer-backed or local-only contexts should use a bounded read/connect test unless a safe signer-mediated write test is explicitly implemented. Relay test diagnostics may include relay URL, status class, latency, event counts, and safe truncated relay errors only. Failed tests may be overridden by explicit user confirmation.

When the local app relay list changes, Other Note should execute a migration before finalizing the new settings. Migration identifies added and removed relays, fetches signed encrypted kind `30078` note events from the current relays before removal, merges fetched ciphertext events into the durable encrypted event cache, selects the latest signed encrypted event for each account/kind/d-tag address, and republishes those existing signed encrypted events to newly added relays. Delete/tombstone state is preserved because the latest signed encrypted event for the note address is republished even when it represents a tombstone. Migration must not decrypt and re-encrypt notes as its primary path, must never emit plaintext fallback data, and must not touch NIP-46 signer-transport relays.

If removed relays cannot be fetched or added relays reject republished encrypted events, the UI should keep the previous relay settings until the user explicitly continues. Continuing may queue pending writes containing only the signed encrypted events and safe failed-relay metadata. Cancelling must preserve the previous relay settings. If no account pubkey is available, relay settings may still be saved only after the user acknowledges that note migration requires sign-in.

Other Note uses NIP-65 kind `10002` relay-list metadata for local app relays. On login and when Relay Settings opens, the app fetches the latest valid published relay list for the active user from the configured discovery relays. Published `write` `r` tags, plus unmarked `r` tags that imply both read and write, update the local app relay list; read-only relays do not become app note relays. If no published list exists, fetch fails, or the list has no write relays, local settings are retained. Relay Settings must not silently overwrite unsaved local edits, active add/test dialogs, or migration in progress; when unsaved edits exist and a different published list is found, the user chooses whether to keep edits or reload the published list. When relay settings change, Other Note signs and publishes an updated kind `10002` event to the union of old and requested app relays before encrypted note migration finalizes. The update replaces only write/outbox app relays and preserves read/inbox, custom/unknown marker, and non-`r` metadata where possible. Relay Settings also exposes a manual Sync/Migrate action that can run content migration against the current app relay list after a published relay import: it fetches encrypted kind `30078` events, merges the encrypted cache, selects latest signed encrypted events including tombstones, and republishes those existing signed encrypted events to current app relays. Relay-list metadata is public and must never include note plaintext, `nsec`, private keys, NIP-46 token secrets, or decrypted payload JSON.

## NIP-46 Remote Signer Plan

NIP-46 remote signer / bunker support is a signer-mediated account mode distinct from Android NIP-55:

- NIP-55 talks to an Android signer app on the same device.
- NIP-46 talks to a remote signer pubkey through relays using encrypted kind `24133` request and response events.
- Other Note creates a local communication keypair for the NIP-46 transport session. This keypair is not the user account key, but it is a sensitive bearer capability because it can send approved signer requests. After successful pairing it may be stored as a saved remote-signer session so the same bunker can be reused after logout or restart.
- For direct `bunker://` login, the `connect` request's first param is the remote signer pubkey from the token, not the temporary communication pubkey. The temporary communication pubkey is only the kind `24133` event author and NIP-44 transport key.
- Other Note requests the minimum capabilities needed for encrypted notes and relay-list metadata: `get_public_key`, kind-scoped `sign_event:30078`, kind-scoped `sign_event:10002`, `nip44_encrypt`, `nip44_decrypt`, `ping`, and `switch_relays`.
- The remote signer returns the user account pubkey through `get_public_key`; that returned user pubkey is the Other Note account identity.
- NIP-46 signer-transport relays are separate from encrypted note relays. Signer relays carry kind `24133` app/signer traffic; note relays carry signed encrypted kind `30078` note events.
- A bunker or signer may choose, inject, or return transport relays that are not in Other Note's editable note relay list. Write-restricted signer relays can reject the temporary NIP-46 communication pubkey even when the final user pubkey is allowed.
- Bunker token order, signer metadata order, and `switch_relays` order are advisory only. Runtime relay choice must be based on behavior: subscribe on a candidate relay, publish the request from the temporary communication pubkey to that same relay, and prefer relays that actually return a matching response.
- Signer-transport relay rejection must be reported as remote signer transport failure, not app note relay failure. Failed signer transport must not create a false local note or enqueue an app note pending write.
- The remote signer performs `sign_event`, `nip44_encrypt`, and `nip44_decrypt`. The user private key stays with the remote signer, but the remote signer may see plaintext note payloads during encryption and decryption operations by design.
- Other Note validates remote-signed note events before publishing: expected kind, content, tags, timestamp, user pubkey, id, and signature must match.
- Durable relay stores may continue to persist signed encrypted Nostr events and safe relay metadata only. They must not persist bunker token secrets, NIP-46 request/response plaintext, `nsec`, user private keys, decrypted notes, decrypted payload JSON, or NIP-44 plaintext. Saved NIP-46 communication keys must live in the separate remote-signer session store, not note caches, pending writes, relay settings, profile cache, or saved-nsec identity storage.
- Pending relay writes retry by republishing already signed encrypted events for the same account pubkey. They do not require storing account private keys or old raw signer requests.
- Android NIP-46 connect, request, response-wait, timeout, encrypt/decrypt, and sign-event work must run on background coroutine dispatchers. UI event handlers should start work and return immediately so signer approval or response timeouts cannot trigger an Android ANR.
- The pairing UI should make the session state visible: checking the bunker link, waiting for signer approval, reading the account public key, connected, or failed with safe user-facing copy. It should also distinguish "Continue with saved remote signer" from "Pair a new remote signer." It must not show token secrets, saved communication private keys, or raw request/response payloads.
- Editor save should show remote signer progress and failures inline while keeping the draft open. Cancel must remain available during signer waits.

Troubleshooting a real bunker should use only safe diagnostics. If a connection fails before approval, verify whether the relay accepted a kind `24133` write from the temporary communication pubkey. Some write-restricted relays reject new temporary pubkeys even when the final user pubkey is allowed; initial compatibility tests should use a public relay accepted by both the app and bunker. If Amber or another bunker injects a write-restricted personal relay, Other Note should mark only that relay failed, continue with other candidates, and report a signer-transport rejection only if every candidate fails. Treat `bunker://` secrets as one-time sensitive tokens and regenerate them after sharing screenshots or logs. Current response handling uses a dedicated live NIP-46 relay path: subscribe for kind `24133` responses on a relay before publishing the encrypted request to that same relay, race relays, ignore stale responses with non-matching decrypted request IDs, and return the first matching response. The session records safe relay health, including source label, publish acceptance/rejection, matched response, latency, and failure reason; subsequent requests prefer the fastest relay that has returned a matching response. `switch_relays` output remains advisory until a returned relay proves writable and returns a matching response. Timeouts include safe method-aware lifecycle fields such as method, short request ID prefix, relay source, per-relay subscribed booleans, per-relay publish status, candidate count, decrypt failure count, mismatched ID count, matched-response boolean, live-response-after-publish boolean, and elapsed time. If Amber approval appears and Other Note later times out, preserve that as a response-path diagnosis while confirming the app remains responsive and no Android ANR is produced.

Saved NIP-46 session records may include the returned user pubkey, client communication pubkey, client communication private key, remote signer pubkey, signer transport relay URLs, non-secret label/status metadata, and timestamps. They must not include the one-time pairing secret after successful connect, auth challenge URLs, raw JSON-RPC payloads, decrypted note content, `nsec`, or the user's private key. If a bunker says Other Note is already connected but no saved remote-signer session exists locally, the user should forget/revoke the old app connection in the signer and pair again.

## Desktop Plan

Saved-device key storage may be enabled only through OS credential stores:

- Debian/Linux: Secret Service/libsecret or an equivalent OS keyring. Other Note's desktop implementation uses the libsecret-compatible `secret-tool` path when available and stores one app-scoped secret per account pubkey.
- Windows: Windows Credential Manager, optionally gated by Windows Hello or user presence where feasible.
- macOS: macOS Keychain.

If an OS keyring is unavailable, locked, unsupported, or not implemented, saved-key mode must be disabled and session-only mode used. There must be no plaintext file fallback. Keyring storage is device-local convenience storage, not hardware-isolated signing and not a backup. It protects the key at rest and behind the desktop/keyring unlock boundary, but authorized local apps or keyring tools may be able to retrieve the saved `nsec` after the keyring is unlocked. Users still need another copy of the `nsec` or a signer-managed identity if they need recovery.

The desktop relay runtime may cache signed encrypted Nostr events and pending relay-write metadata under the user's local app data directory. That cache is not key storage: it must not contain `nsec` values, private keys, decrypted note bodies, decrypted payload JSON, or NIP-44 plaintext. It may contain encrypted event content because that is the same signed ciphertext intended for public relays. Desktop direct-key sessions remain session-only unless explicitly saved to the OS keyring: production crypto can use the in-memory key to encrypt, decrypt, sign, fetch, publish, edit, and tombstone notes, but Other Note still does not persist the plaintext key in app files.

## iOS Plan

iOS saved-device key storage must use iOS Keychain when implemented. There must be no plaintext fallback.

## Web App Plan For othernote.com

The future web app must be a static/client-side app. All signing, encryption, and decryption must happen in the browser or client device.

The web app must not perform:

- Server-side note processing.
- Server-side signing.
- Server-side encryption or decryption.
- Server-side storage of `nsec` values or private keys.

Preferred web auth/signing order:

1. NIP-07 browser extension signer where available.
2. NIP-46 remote signer/bunker.
3. Session-only pasted `nsec` fallback.

Direct `nsec` paste on web is allowed only as a fallback. The key must be kept in memory only:

- Do not store it in `localStorage`.
- Do not store it in IndexedDB.
- Do not store it in cookies.
- Do not store it in server sessions.
- Do not store it in analytics, logs, or crash reports.
- Clear the input after login.

Avoid third-party scripts and analytics where feasible. Use HTTPS only and a strict Content Security Policy. Web delivery has a larger trust surface than native apps because served JavaScript can change.

## Threat Model And Non-goals

This policy is designed to prevent accidental plaintext private-key persistence, accidental logging, and accidental server/relay exfiltration of keys or decrypted note content.

This policy currently does not cover completed implementations for:

- Saved-device Android `nsec` storage.
- Windows/macOS saved-device key storage.
- Durable NIP-46 session metadata persistence.
- NIP-07 web signer support.
- A web app.
- Custom password-vault or password-derived encrypted file storage.

Secure storage implementations must be added platform by platform and tested before saved-key mode is enabled on that platform. A test fake may exist only in tests and must be clearly marked as a test fake.

## References And Planned Targets

- NIP-55 Android signer: <https://github.com/nostr-protocol/nips/blob/master/55.md>
- NIP-46 remote signer: <https://github.com/nostr-protocol/nips/blob/master/46.md>
- NIP-07 browser extension signer: <https://github.com/nostr-protocol/nips/blob/master/07.md>
- Android credential and keystore concepts: <https://developer.android.com/privacy-and-security/keystore>
- Linux Secret Service/libsecret: <https://specifications.freedesktop.org/secret-service-spec/latest/> and <https://wiki.gnome.org/Projects/Libsecret>
- Windows Credential Manager: <https://learn.microsoft.com/en-us/windows/win32/secauthn/credential-management>
- Windows Hello concept: <https://learn.microsoft.com/en-us/windows/security/identity-protection/hello-for-business/>
- macOS Keychain: <https://developer.apple.com/documentation/security/keychain_services>
- iOS Keychain: <https://developer.apple.com/documentation/security/keychain_services>
