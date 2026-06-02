# Other Note

Other Note is a GPLv3 Nostr-powered notes app foundation for private notes. The first-pass app targets Android and Debian/Linux desktop with a shared Kotlin Multiplatform and Compose architecture.

## Status

This repository now contains a runnable project structure with shared domain, data, sync, Nostr abstraction, UI, Android entry point, desktop entry point, desktop Debian packaging configuration, and focused tests.

Important security status:

- GPLv3 license is preserved in `LICENSE`.
- Private keys and decrypted note bodies are not logged.
- `nsec` input is only kept in memory. The UI redacts it immediately after validation.
- "Create new identity" generates a fresh Nostr private key and shows the resulting `nsec` so the user can save or import it. Other Note does not persist the generated plaintext `nsec`.
- The login field hides pasted `nsec` text and clears it after successful validation or local-only entry.
- Local-only mode is explicit. Notes can be created, viewed, edited, and deleted without a Nostr session.
- Linux desktop saved-key support is opt-in through the desktop keyring/Secret Service path when `secret-tool` is available. Session-only remains the default, Android saved-key storage is not implemented, and there is no plaintext file fallback.
- `ProductionNostrCryptoFactory` is enabled for direct session-only signing, NIP-44 v2 encryption/decryption, and relay-backed tests where a real relay client is wired. `NonProductionNostrCrypto` remains the safety fallback.
- `NonProductionNostrCrypto` remains available and refuses secp256k1 signing and NIP-44 encryption/decryption so plaintext notes are not accidentally published.
- Direct `nsec` and generated-identity use remains session-only unless the desktop user explicitly saves the key to the OS keyring. These sessions can encrypt/sign/publish/fetch when production crypto and a real relay client are available. Bounded desktop/JVM and Android WebSocket relay clients surface explicit per-relay status instead of fake success.
- Desktop relay runtime is enabled by default when production crypto is available. It can fetch and publish encrypted note events through configured relays for session-only direct keys and signer-backed sessions.
- Desktop relay runtime stores a local encrypted event cache and pending outbound write queue under `~/.local/share/other-note/`. These files contain signed encrypted Nostr events and relay metadata only, never `nsec` values, private keys, decrypted note bodies, decrypted payload JSON, or NIP-44 plaintext.
- Android can detect generic NIP-55 external signer apps, request signer public identity, request a harmless local test-event signature, run a harmless local NIP-44 encrypt/decrypt round trip, build/verify an unpublished signer-backed kind `30078` note event, and create/edit/delete/fetch relay-backed signer notes from the normal editor without importing or storing `nsec`.
- Android external-signer relay runtime stores a durable encrypted event cache and pending outbound write queue in app-private no-backup storage. These files contain signed encrypted Nostr events and safe relay metadata only, never `nsec` values, private keys, decrypted note bodies, decrypted payload JSON, or NIP-44 plaintext.
- NIP-46 remote signer foundation support can parse `bunker://` tokens, create session-only NIP-46 transport keys, request remote signer public keys, request NIP-44 encrypt/decrypt, request event signing, and validate returned signed events before relay publish. The account identity is the user pubkey returned by the remote signer, not the local NIP-46 transport pubkey.
- Sync is non-destructive when crypto is disabled, relay reads fail, or no relay reports a successful read.
- Payload JSON uses `kotlinx.serialization`. NIP-01 event preimage serialization is kept separate from note payload serialization.

## Nostr Data Model

Other Note uses NIP-78 application-specific data events:

- Event kind: `30078`
- Stable addressable `d` tag: `other-note:note:<note_id>`
- Tags:
  - `["d", "other-note:note:<note_id>"]`
  - `["t", "other-note"]`
  - `["alt", "Encrypted Other Note note"]`
  - `["client", "Other Note"]`
- Event pubkey is the user's public key.
- Event content is intended to be the NIP-44 v2 encrypted note payload JSON, encrypted to self.

The encrypted note payload schema is versioned:

```json
{
  "schema": "com.libertasprimordium.othernote.note.v1",
  "note_id": "<stable-note-id>",
  "created_at_ms": 1234567890,
  "updated_at_ms": 1234567890,
  "body_markdown": "raw markdown text",
  "deleted": false
}
```

Edits publish a new signed event with the same kind/pubkey/d-tag. Relay results are grouped by d tag and note id; the newest event by `created_at` wins. If timestamps tie, NIP-01 replaceable/addressable behavior retains the event with the lowest event id in lexicographic order. Local state and durable encrypted-event caches may retain historical encrypted versions, but visible notes are materialized from the latest valid version and stale sync/cache results must not overwrite a newer local edit. Deletion uses an app-level tombstone with `deleted=true` and an empty body so relay DELETE support is not required.

## Relay Retention

Default app/note relays are editable and persist locally on Android and desktop:

- `wss://relay.damus.io`
- `wss://relay.primal.net`
- `wss://relay.nostr.net`
- `wss://nos.lol`
- `wss://relay.ditto.pub`

Relay settings edit the app relays used for encrypted kind `30078` note fetch/publish. They do not edit NIP-46 remote-signer transport relays, which are sourced from bunker tokens and signer transport state for encrypted kind `24133` request/response traffic.

Other Note also reads and publishes NIP-65 relay-list metadata for these app relays. On login, the app looks for the user's latest public kind `10002` relay-list event on the configured discovery relays. If a published list exists and contains write/outbox relays, those write relays become the local app relay list; if no list exists or no write relays are present, local settings are retained. Local app relays are represented as `write` `r` tags. Existing read/inbox, custom, and non-`r` relay-list metadata from other apps is preserved where possible. NIP-46 signer-transport relays are never imported or published as app relays just because a bunker used them.

Opening Relay Settings runs a bounded one-shot refresh of the active user's latest published relay list. If published write relays changed elsewhere, they are imported into local app relay settings unless the user has unsaved relay edits, an add/test dialog, or migration in progress. Unsaved edits prompt a choice to keep local edits or reload the published list.

Users may enter relay hostnames with or without `wss://`; for example, `relay.primal.net` is normalized to `wss://relay.primal.net`. Production relays should use `wss://`; `ws://` is accepted only for local development hosts such as `localhost`. Query strings, fragments, malformed URLs, duplicate normalized URLs, and `http://` or `https://` URLs are rejected. The settings screen can restore the default relay set, and saving an empty relay list is blocked because relay sync and publishing require at least one app relay.

New relays are tested before being added. Direct/session-only key sessions publish and fetch a harmless non-note test event from the candidate relay. Signer-backed or local-only contexts use a bounded read/connect test instead, so relay testing does not mutate NIP-46 signer-transport relays or issue raw signer request payloads. If the test succeeds, the relay is added silently. If it fails, Other Note shows a safe warning and asks whether to cancel or continue adding the relay anyway.

Public relays may purge old events. Add a relay you control for stronger long-term retention.

Relay changes execute a local migration plan before settings are finalized. Other Note first signs and publishes an updated public kind `10002` relay-list event to the union of old and requested app relays, then fetches signed encrypted note history from the current relays before removal, keeps the encrypted event cache, selects the latest signed encrypted event for each note address, and republishes those already-signed encrypted events to newly added relays. If relay-list publishing, removed-relay fetch, or added-relay writes partially fail, the settings screen shows a safe warning and lets the user cancel or continue. Migration never decrypts and republishes plaintext, and it does not touch NIP-46 signer-transport relays.

Relay Settings also has a manual Sync/Migrate action for the current app relay list. Use it after importing a relay-list change from another app: it fetches encrypted note events from the current app relays, merges them into the encrypted cache, selects the latest signed encrypted event per note address including tombstones, and republishes those existing signed encrypted events to the current app relays without requiring a local add/remove edit.

## Key Management And nsec Safety

Current direct `nsec` use is session-only by default. On Linux desktop, the user may explicitly save an identity to the desktop keyring/Secret Service path when available. Other Note does not persist `nsec` values or private keys in app files, relay settings, caches, pending-write stores, logs, or preferences. Desktop keyring storage is device-local convenience storage: it protects the key at rest and behind the desktop/keyring unlock boundary, but authorized local apps or keyring tools may be able to retrieve the saved key after the keyring is unlocked.

The sign-in screen can create a fresh Nostr identity. This generates a new private key, displays the `nsec` for the user to save in a secure password manager, OS credential store, or signer such as Amber, and requires explicit acknowledgement before the key is used for the current session. On Linux desktop, the generated identity can also be saved explicitly to the desktop keyring. When production crypto and a relay client are available, the generated session can encrypt, sign, publish, decrypt, edit, and delete encrypted `kind: 30078` notes. Losing the generated `nsec` means losing access to encrypted notes for that identity if no signer/keyring/password-manager copy exists. Other Note does not silently store or recover it.

The key-management policy is documented in [docs/key-management.md](docs/key-management.md). The sign-in screen prioritizes Android signer/NIP-55 first on Android, then NIP-46 remote signer/bunker when available, then session-only pasted `nsec`, with fresh identity generation as a lower-emphasis deliberate flow. Planned key paths prefer external signers first, then session-only pasted `nsec`, then saved-device `nsec` only through OS-backed credential storage. The future web app must keep signing, encryption, and decryption fully client-side.

## Android External Signer Status

Android builds include NIP-55 discovery, public-key request, internal coverage for signer primitives, and relay-backed signer note creation/edit/delete/recovery:

- The manifest declares a `nostrsigner:` query so the app can discover compatible Android signer apps.
- Discovery is generic NIP-55 intent discovery, not Amber-only. Amber is the primary planned/tested signer target, but any compatible signer can be detected.
- When a NIP-55 signer is detected, the login screen presents Android signer as the recommended Android path, shows remote signer/bunker as an advanced secondary option when available, and keeps the direct `nsec` field as a lower-emphasis session-only fallback.
- Pressing "Use Android signer" explicitly launches a NIP-55 `get_public_key` intent. If the signer approves, Other Note creates an in-memory signer-backed session with public key, `npub`, and signer package metadata only.
- Internal tests and helper code cover the NIP-55 `ContentResolver` `SIGN_EVENT` path using harmless unpublished events. These development test actions are no longer exposed in the normal app UI.
- The initial `get_public_key` request asks for optional `sign_event` permissions for kind `1`, encrypted note kind `30078`, and relay-list kind `10002`, plus NIP-44 encrypt/decrypt permissions so compatible signers can approve the ContentResolver requests.
- Signer-built note events are validated locally: pubkey must match the signer-backed session, content/kind/tags must match the request, and the NIP-01 event id/signature must verify.
- Signer NIP-44 encrypt/decrypt operations are used by the real note save/edit/delete/recovery workflow. The app does not display plaintext payloads, ciphertext, signatures, full event JSON, or full public keys.
- Normal editor save/edit in an Android signer session builds a signed encrypted kind `30078` event through signer NIP-44 plus `SIGN_EVENT`, validates/decrypts/decodes it, publishes it to the configured relays, and displays the note after at least one relay accepts the write. Delete creates a signer-backed tombstone event with the same d tag, publishes it, and hides the note after at least one relay accepts.
- Android signer login fetches kind `30078` Other Note events for the signer pubkey from the configured relays, validates id/signature before decrypting, decrypts through signer NIP-44, decodes `NotePayload`, reduces replacements/tombstones, and updates visible notes incrementally as relays return usable events.
- Android signer relay cache and pending writes are durable in app-private no-backup storage. The cache stores signed encrypted kind `30078` events for the signer pubkey so notes can be recovered after relaunch before or alongside relay fetch. The pending-write queue stores already signed encrypted events, target relays, relay statuses, retry counts, safe truncated relay errors, and timestamps so unfinished fanout can retry on the next signer login or sync.
- Safe NIP-55 diagnostics can be enabled with `OTHER_NOTE_SHOW_NIP55_DIAGNOSTICS=1` or `-Dothernote.showNip55Diagnostics=true`. Diagnostics include only path, shape, lengths, booleans, abbreviated ids/pubkeys, result status, and result column/key names.
- No `nsec` or private key is stored, logged, or sent to a relay/server as part of signer discovery.

Manual Android signer test with Amber or another NIP-55 signer:

1. Install a NIP-55-compatible Android signer such as Amber.
2. Install/run the debug Android build.
3. Tap "Use Android signer."
4. Approve the public-key request in the signer.
5. Confirm Other Note shows the abbreviated `npub` and starts a relay sync.
6. Confirm the old development-only signer test buttons are not visible in the normal notes UI.
7. Create a normal note through the editor and tap "Save."
8. Approve required signer encrypt/sign/decrypt requests.
9. Confirm at least one relay accepts the write and the note appears.
10. Force stop or relaunch the app, use Android signer again, and confirm the cached note can appear before or alongside relay fetch.
11. Confirm the note is fetched, decrypted, and displayed after relay sync completes.
12. If practical, simulate partial relay fanout by including one unreachable relay, relaunch, log in with Android signer, and confirm the pending write retries on the next sync/login.
13. Inspect app-private durable store files under the debug app data directory and confirm no `nsec`, private key, decrypted note body, or decrypted payload JSON such as `body_markdown` appears.
14. Edit the note, approve signer requests, relaunch, and confirm the replacement appears.
15. Open the note, tap "Delete", and confirm.
16. Approve required signer encrypt/sign/decrypt requests and confirm the note disappears after at least one relay accepts the tombstone.
17. Relaunch and confirm the tombstoned note stays hidden.
18. If signer operations fail, enable NIP-55 diagnostics and capture only the safe request path, payload length, field booleans, result status, and result columns.
19. If relay recovery fails, enable relay diagnostics and capture only safe per-relay status, counts, and rejection classes.
20. Confirm the direct `nsec` fallback remains hidden/password-style and session-only.

## NIP-46 Remote Signer Status

Other Note includes a first production-safe foundation for NIP-46 remote signer / bunker login:

- The login screen accepts a `bunker://<remote-signer-pubkey>?relay=<relay>&secret=<optional-secret>` token.
- The app creates a fresh session-only local NIP-46 communication keypair for the connection. This keypair is distinct from the user account key and is not persisted in this pass.
- NIP-46 request and response events use kind `24133` and are encrypted through NIP-44 between the session communication key and the remote signer pubkey.
- Direct `bunker://` connect requests send the remote signer pubkey from the token as the first `connect` param, followed by the optional token secret and requested Other Note permissions. The temporary communication pubkey is only the request event author and transport encryption key.
- After connect, Other Note calls `get_public_key` and uses that returned user pubkey as the note account identity.
- NIP-46 signer-transport relays are separate from the app's encrypted note relays. Signer relays carry kind `24133` app/signer requests and responses; note relays carry signed encrypted kind `30078` note events.
- A bunker or signer may return or internally use its own transport relays. Those relays may differ from Other Note's editable note relay list.
- Relay order from bunker tokens, signer metadata, or `switch_relays` is treated as advisory. Other Note keeps all valid signer relays as candidates, records session-local relay behavior, and prefers relays that actually accepted the temporary communication pubkey and returned a matching response.
- Write-restricted signer relays can reject the temporary NIP-46 communication pubkey even when the final user account pubkey is allowed. Other Note treats that as a remote signer transport failure, not as an encrypted note publish failure.
- Remote `sign_event` responses are validated for expected kind, content, tags, timestamp, pubkey, id, and signature before any relay publish.
- Remote `nip44_encrypt` and `nip44_decrypt` are used for note save/edit/delete/recovery. The remote signer may see plaintext note payloads during these operations by design; use only a remote signer you trust with note plaintext.
- Durable event cache and pending-write stores remain unchanged: they persist only signed encrypted events and safe relay metadata scoped by account pubkey. NIP-46 token secrets, transport private keys, decrypted notes, and decrypted payload JSON are not persisted.
- Pending relay writes retry by republishing already signed encrypted events for the same user pubkey; retry does not require storing a user private key.
- Client-initiated `nostrconnect://` URI generation is available in the protocol layer for tests/future UI, but the current usable login path is pasted `bunker://`.
- Android NIP-46 login and remote signer requests run from background coroutines. Relay publish/fetch waits, response polling, JSON parsing, and NIP-44/signing work must not block Compose input dispatch or recomposition.
- Editor save keeps the draft open while remote signer requests are pending. Signer-transport failures are shown inside the editor and do not create a local note or enqueue an app note pending write.

Manual NIP-46 bunker checklist:

1. Start or choose a NIP-46-compatible bunker/remote signer.
2. Copy a `bunker://` token with at least one `wss://` relay.
3. Paste the token into "Paste bunker:// remote signer token" and connect.
4. Confirm Other Note displays the connected user `npub`.
5. Save a note and approve the remote signer flow where applicable.
6. Confirm the published event is encrypted kind `30078`.
7. Relaunch the app, reconnect the same remote signer, and confirm cached encrypted events and relay fetch can restore notes.
8. Edit the note and confirm replacement sync.
9. Delete the note and confirm the tombstone hides it after relay acceptance.
10. Inspect durable store files and confirm they contain no `nsec`, private key, bunker secret, plaintext note body, or decrypted payload JSON such as `body_markdown`.
11. Reject or cancel a remote signer request and confirm the app shows a clean error without changing local notes.
12. Stop the remote signer or relay and confirm timeout/failure messages do not expose token secrets, plaintext, or raw signer responses.
13. Test partial relay failure and confirm local visible notes are preserved.
14. If the signer uses a write-restricted kind `24133` transport relay, confirm the editor stays open and shows a remote signer relay rejection rather than hanging or navigating away.

NIP-46 bunker troubleshooting:

- If connection fails during the first request, check whether at least one configured relay accepted a kind `24133` write from Other Note's temporary NIP-46 communication pubkey.
- Whitelist or write-restricted relays may reject the temporary communication pubkey even when the eventual user account pubkey is allowed.
- This is distinct from encrypted note publish failure. A signer-transport failure affects kind `24133` app/signer requests; note-publish failure affects kind `30078` encrypted note events sent to the app's note relays.
- For first compatibility testing, use a public relay that accepts writes from new pubkeys and is also watched by the bunker. If Amber or another bunker injects a write-restricted personal relay, Other Note reports a signer-transport failure rather than waiting for a response that cannot arrive.
- Relays may return stale kind `24133` responses for the same temporary communication pubkey. Other Note ignores decrypted responses whose `id` does not match the current request and times out with safe candidate/decrypt/mismatch counts if no matching response arrives.
- Remote signer timeouts include the safe NIP-46 method name, short request ID prefix, relay source, attempted relay count, per-relay subscribed booleans, per-relay publish status, response candidate count, decrypt failure count, mismatched ID count, matched-response boolean, and elapsed time. During save this distinguishes encryption, signing, and decryption requests.
- Treat `bunker://` secrets as sensitive and one-time-use. Regenerate the token after sharing screenshots, logs, or relay diagnostics.
- Other Note uses the remote-signer-returned user pubkey as the account identity. The temporary NIP-46 transport pubkey is not the note account and is not persisted.
- NIP-46 request/response handling uses a dedicated live relay path: for each signer transport relay, Other Note opens the kind `24133` response subscription before publishing the encrypted request to that same relay, races relays, and returns the first decrypted response whose request ID matches. It no longer treats remote signer requests as normal note fanout plus post-publish history fetch.
- Signer relay selection is behavior-based, not order-based. If one candidate rejects the temporary communication pubkey, only that relay is marked failed and the request continues on other candidates. `switch_relays` output remains advisory until a returned relay proves it can accept the request and return the matching response.
- If Amber approval appears but Other Note later times out, this is still a response-path issue; the app should remain responsive for the full timeout window and must not show an Android ANR.

## Build And Run

Prerequisites:

- JDK 17 or newer.
- Android SDK for Android builds.
- Network access the first time Gradle resolves Compose Multiplatform and Kotlin plugin artifacts.
- `local.properties` is required for Android builds when `ANDROID_HOME` is not set. It should contain `sdk.dir=/path/to/android/sdk` and must not be committed.
- Debian packaging requires a full JDK 17+ with `jpackage`. Android Studio's bundled JBR is not enough.

Commands:

```sh
./gradlew :app:run
./gradlew :app:desktopTest
./gradlew :app:desktopJar
./gradlew :app:assembleDebug
./gradlew :app:packageDeb
./gradlew :app:check
```

Desktop relay runtime is enabled by default when production crypto is available. The legacy developer flag is still accepted for compatibility, but it is no longer required:

```sh
OTHER_NOTE_ENABLE_DEV_RELAY_RUNTIME=1 ./gradlew :app:run
```

The equivalent JVM property is `-Dothernote.devRelayRuntime=true`. A pasted `nsec` is used only for the active process session unless the desktop user explicitly saves it to the OS keyring. Saved desktop identities are loaded from the keyring by account pubkey and are shown on the sign-in screen using safe metadata only. Other Note does not write plaintext keys to local files, relay settings, cache, logs, preferences, or test output.

Desktop relay runtime keeps a local durability layer at `~/.local/share/other-note/`:

- `event-cache/` stores signed encrypted kind `30078` events keyed by account pubkey.
- `pending-writes/` stores already signed encrypted events plus target relay URLs, accepted/failed relay status, retry counts, and safe error strings.
- `relay-settings.json` stores only the local app relay URL list.
- Pending writes are retried on login, sync, or manual refresh for the same account pubkey. The retry path republishes the existing signed event and does not require storing or re-signing with an old private key.
- The retry policy is intentionally bounded for now: unfinished relays are retried up to three times per stored pending write. A pending write is removed after every target relay accepts it or all unfinished target relays hit the retry cap.
- Deleting this local directory clears the cache and pending retry queue, but it does not delete events already published to relays.

Android external-signer relay runtime keeps the same durable encrypted event and pending-write record shape in app-private no-backup storage:

- `event-cache/` stores signed encrypted kind `30078` events keyed by account pubkey.
- `pending-writes/` stores already signed encrypted events plus target relay URLs, accepted/failed relay status, retry counts, safe error strings, and timestamps.
- `relay-settings.json` stores only the local app relay URL list.
- The files are used only after Android signer login for the same public key. Loading the encrypted event cache does not require network or signer access, but decrypting cached events still requires signer-mediated NIP-44 decrypt for the active session.
- Direct `nsec` fallback remains session-only unless explicitly saved to the Linux desktop keyring. When production crypto and a relay client are available it can publish/recover encrypted notes; otherwise it remains local/offline and must not emit plaintext. Android saved-device key storage remains future work and must use OS-backed secure storage when implemented.

Normal UI shows compact relay status only. To show verbose safe relay diagnostics while debugging, launch with:

```sh
OTHER_NOTE_SHOW_RELAY_DIAGNOSTICS=1 ./gradlew :app:run
```

The equivalent diagnostics JVM property is `-Dothernote.showRelayDiagnostics=true`.

Desktop relay runtime defaults:

- `wss://relay.damus.io`
- `wss://relay.primal.net`
- `wss://relay.nostr.net`
- `wss://nos.lol`
- `wss://relay.ditto.pub`

Manual relay checklist:

1. Create or obtain a throwaway `nsec`.
2. Launch with `./gradlew :app:run`.
3. Log in with the throwaway `nsec` and confirm the abbreviated `npub` appears.
4. Create a note and verify at least one relay accepts the write.
5. Restart the desktop app, log in with the same throwaway `nsec`, and refresh/fetch the note.
6. Edit the note and verify refresh keeps only the newest version.
7. Delete the note and verify refresh/restart hides the tombstoned note.

Manual generated-identity checklist:

1. Open the sign-in screen.
2. Tap "Create new identity."
3. Confirm the warning screen explains that the generated `nsec` is a private key and cannot be recovered by Other Note.
4. Generate the identity and confirm an `npub` appears.
5. Reveal the `nsec` only when ready to save it.
6. Confirm "Use for this session" stays disabled until both acknowledgements are checked.
7. Save/import the `nsec` outside Other Note, then use it for the session.
8. Create, edit, and delete a note. Confirm no "NIP-44 v2 encryption is not wired yet" warning appears when production crypto is available.
9. Logout or force-stop/relaunch and confirm the generated key is not remembered automatically.
10. Re-enter the saved `nsec` through the direct session-only path or import it into a signer, then sync and confirm encrypted notes can be recovered/decrypted.
11. Inspect app-private durable stores and confirm the exact generated `nsec`, raw private key, plaintext note body, `body_markdown`, and token secrets are absent.
12. Confirm Android NIP-55 and NIP-46 login paths still appear and work as before.

Runtime troubleshooting:

- Save/edit/delete is best-effort. A note becomes locally visible after the local encrypt/sign/validate/decrypt control passes and at least one relay accepts the write.
- Remaining relay writes continue in the background after the first accepted write. Compact status reports aggregate progress; verbose per-relay status is hidden unless diagnostics are enabled.
- If the app exits after the first accepted relay but before fanout completes, unfinished relay writes remain queued locally and are retried on the next login/sync for the same pubkey.
- Cached encrypted events are loaded after login and decrypted only with the session key supplied for the current process, so notes can appear before network fetches complete.
- Slow relays may fail, reject, or time out. One slow relay should not block a successful write from a faster relay.
- Sync applies valid events incrementally as each relay completes. A recovered note can appear before the final aggregate sync status arrives, and later relay results can still update newer replacements or tombstones.
- Verbose fetch diagnostics include safe timing fields such as `duration_ms`, query shape, fetched event counts, valid event counts, and rejected reason classes. They do not include keys, ciphertext, plaintext, note bodies, or decrypted JSON.
- If no note appears after sync, enable relay diagnostics to distinguish: no relay returned events, returned events were rejected, all relays failed/timed out, or the newest event is a tombstone.
- Partial relay failures are expected on public relays. Retry refresh or remove consistently slow relays from the editable relay list.
- Current desktop and Android relay recovery uses direct NIP-01 filtered fetch: first author/kind/`#t`, then author/kind fallback with local Other Note filtering. NIP-77/negentropy is planned later after encrypted local event cache/index support exists; it learns event IDs and still requires `EVENT`/`REQ` transfer for event bodies.

Saved-device Android key storage, non-Linux desktop keyring backends, durable NIP-46 session metadata, richer client-initiated NIP-46 pairing UI, profile rendering, and inline media rendering are intentionally future work.

If Gradle reports missing plugin artifacts, run with network access so it can fetch GPL-compatible open-source dependencies from Google Maven, Maven Central, and the Gradle Plugin Portal.

## Safe Test Commands

Normal local validation:

```sh
git diff --check
./gradlew :app:desktopTest
./gradlew :app:desktopJar
```

Android validation when the Android SDK is configured:

```sh
./gradlew :app:check
./gradlew :app:assembleDebug
```

Desktop package validation when the active JDK includes `jpackage`:

```sh
./gradlew :app:packageDeb
```

Relay integration tests are intentionally opt-in. They generate throwaway keys at runtime, publish encrypted disposable kind `30078` test blobs, fetch them back, publish an update, and publish an app-level tombstone. Public relays may retain those encrypted blobs.

```sh
OTHER_NOTE_RELAY_TESTS=1 OTHER_NOTE_TEST_RELAYS=wss://relay.example.com ./gradlew :app:desktopTest
```

Public relay behavior varies; the integration test requires at least one configured relay to accept each write and reports per-relay read/write status when assertions fail.

## Crypto Adapter Status

The production crypto boundary is `NostrCrypto` plus `ProductionNostrCryptoFactory`.

The offline production adapter is backed by Quartz `1.11.0` for:

- Throwaway private-key generation.
- NIP-19 `nsec`/`npub` encode/decode.
- Public-key derivation from private key.
- NIP-01 canonical event preimage, id hashing, Schnorr signing, and signature validation.
- NIP-44 v2 encryption/decryption to self.

Quartz is MIT-licensed and therefore GPLv3-compatible. The previous Quartz `1.03.0` adapter was disabled after focused desktop/JVM tests reproduced a local NIP-44 v2 `Invalid Mac` failure when decrypting ciphertext created with the same generated keypair. The current spike upgrades to Kotlin `2.3.21`, Compose Multiplatform `1.11.0`, Android Gradle Plugin `8.13.2`, Gradle `8.13`, Quartz `1.11.0`, kotlinx.coroutines `1.11.0`, kotlinx.serialization JSON `1.11.0`, and Android compile SDK preview `CinnamonBun`.

The real crypto round-trip tests live under `desktopTest`. They generate throwaway keypairs, perform repeated NIP-44 self-encryption round trips, sign and validate kind `30078` events, decrypt event content, and cover tombstone payloads before relay integration tests are run.

## Relay Transport Status

`DesktopNostrClient` and `AndroidNostrClient` implement bounded NIP-01 WebSocket publish and fetch behavior for their runtime targets:

- Sends `["EVENT", event]` and waits for matching `OK`.
- Sends `REQ` filters for author, kind `30078`, `#t=["other-note"]`, and a bounded limit.
- Reads `EVENT`, `EOSE`, `CLOSED`, `NOTICE`, and `OK` relay messages.
- Sends `CLOSE` after bounded fetches.
- Falls back to author/kind filtering if tag filtering does not return usable events.

The desktop client is wired into the normal Debian/desktop runtime when production crypto is available. The legacy `OTHER_NOTE_ENABLE_DEV_RELAY_RUNTIME=1` and `-Dothernote.devRelayRuntime=true` switches are still accepted as compatibility markers but are no longer required for relay fetch/publish. The Android client remains wired for Android external-signer, NIP-46, and production-crypto direct session-only identities. Direct `nsec` fallback remains session-only unless explicitly saved to the Linux desktop keyring; it falls back to local/offline only if production crypto is unavailable.

## Architecture

Shared `commonMain` code is organized into:

- `domain`: notes, relay config, session, sync state.
- `nostr`: NIP-19 decoding, NIP-44 status, event model, crypto/client/repository interfaces.
- `data`: note store, relay settings, profile cache, secure key-store abstraction, encrypted event cache, and pending write queue interfaces.
- `sync`: save, delete/tombstone, sync reduction, and local relay migration execution.
- `ui`: shared Compose screens for login, list, display, edit, and relay settings.
- `util`: URL detection, npub detection, markdown helpers, relay URL validation, payload JSON codec.

Platform code:

- `androidMain`: Android activity, NIP-55 signer adapters, bounded Android relay client, and app-private durable encrypted-event cache/pending-write stores for external-signer sessions.
- `desktopMain`: Compose Desktop window entry point, bounded WebSocket relay client, and file-backed developer runtime cache/pending-write stores.

## Known Limitations And TODOs

- Keep the production crypto adapter covered by offline generated-key tests before expanding runtime relay sync.
- Implement Android encrypted key storage and broaden desktop keyring support beyond Linux Secret Service where needed.
- Fetch and cache kind 0 profile metadata once networking exists.
- Replace minimal markdown rendering with a compatible renderer if one fits licensing and KMP constraints.
- Add inline image/video loading with size limits and timeouts.

## License

Other Note is licensed under the GNU General Public License version 3. See `LICENSE`.
