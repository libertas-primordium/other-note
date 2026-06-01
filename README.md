# Other Note

Other Note is a GPLv3 Nostr-powered notes app foundation for private notes. The first-pass app targets Android and Debian/Linux desktop with a shared Kotlin Multiplatform and Compose architecture.

## Status

This repository now contains a runnable project structure with shared domain, data, sync, Nostr abstraction, UI, Android entry point, desktop entry point, desktop Debian packaging configuration, and focused tests.

Important security status:

- GPLv3 license is preserved in `LICENSE`.
- Private keys and decrypted note bodies are not logged.
- `nsec` input is only kept in memory. The UI redacts it immediately after validation.
- The login field hides pasted `nsec` text and clears it after successful validation or local-only entry.
- Local-only mode is explicit. Notes can be created, viewed, edited, and deleted without a Nostr session.
- Secure private-key persistence is intentionally disabled until platform secure storage is implemented.
- `ProductionNostrCryptoFactory` is enabled for offline desktop/JVM tests after upgrading Quartz and the Kotlin/Compose/AGP toolchain. Normal app usage still uses the safe `NonProductionNostrCrypto` fallback until relay sync and storage behavior are reviewed.
- `NonProductionNostrCrypto` remains available and refuses secp256k1 signing and NIP-44 encryption/decryption so plaintext notes are not accidentally published.
- Normal app runtime still uses an offline relay adapter. A bounded desktop/JVM WebSocket relay client exists for explicit opt-in integration tests only, and publishing/fetching surface explicit per-relay status instead of fake success.
- Desktop can be launched in an explicit developer relay runtime with `OTHER_NOTE_ENABLE_DEV_RELAY_RUNTIME=1`; default desktop and all Android runtime paths remain offline/non-production.
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

Edits publish a new signed event with the same kind/pubkey/d-tag. Relay results are grouped by d tag and note id; the newest event by `created_at` wins. If timestamps tie, NIP-01 replaceable/addressable behavior retains the event with the lowest event id in lexicographic order. Deletion uses an app-level tombstone with `deleted=true` and an empty body so relay DELETE support is not required.

## Relay Retention

Default relays are editable:

- `wss://relay.damus.io`
- `wss://relay.primal.net`
- `wss://relay.nostr.net`
- `wss://relay.ditto.pub`

Public relays may purge old events. Add a relay you control for stronger long-term retention.

Relay changes are planned through a migration use case that identifies added and removed relays. The intended production behavior is to fetch from old/removing relays, reduce to current note state, republish current signed events to added relays, and only then finalize settings. The first pass stores the plan and surfaces limitations because relay networking is not wired yet.

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

Default runtime remains offline. For manual desktop-only relay testing with throwaway keys:

```sh
OTHER_NOTE_ENABLE_DEV_RELAY_RUNTIME=1 ./gradlew :app:run
```

The equivalent JVM property is `-Dothernote.devRelayRuntime=true`. Use throwaway `nsec` values only. The pasted `nsec` is used only for the active process session, is redacted after login, and is not written to local files, relay settings, cache, logs, or test output. Saved-key mode is intentionally unavailable until OS keyring or external signer support is implemented.

Developer relay runtime defaults:

- `wss://relay.damus.io`
- `wss://relay.primal.net`
- `wss://relay.nostr.net`
- `wss://nos.lol`
- `wss://relay.ditto.pub`

Manual relay checklist:

1. Create or obtain a throwaway `nsec`.
2. Launch with `OTHER_NOTE_ENABLE_DEV_RELAY_RUNTIME=1 ./gradlew :app:run`.
3. Log in with the throwaway `nsec` and confirm the abbreviated `npub` appears.
4. Create a note and verify at least one relay accepts the write.
5. Restart in developer relay mode, log in with the same throwaway `nsec`, and refresh/fetch the note.
6. Edit the note and verify refresh keeps only the newest version.
7. Delete the note and verify refresh/restart hides the tombstoned note.

OS keyring persistence, Amber/NIP-55, NIP-46, profile rendering, and inline media rendering are intentionally future work.

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

`DesktopNostrClient` implements bounded NIP-01 WebSocket publish and fetch behavior for desktop/JVM tests:

- Sends `["EVENT", event]` and waits for matching `OK`.
- Sends `REQ` filters for author, kind `30078`, `#t=["other-note"]`, and a bounded limit.
- Reads `EVENT`, `EOSE`, `CLOSED`, `NOTICE`, and `OK` relay messages.
- Sends `CLOSE` after bounded fetches.
- Falls back to author/kind filtering if tag filtering does not return usable events.

This client is wired only into desktop developer relay runtime when `OTHER_NOTE_ENABLE_DEV_RELAY_RUNTIME=1` or `-Dothernote.devRelayRuntime=true` is set. Public relay sync remains unavailable in the default runtime.

## Architecture

Shared `commonMain` code is organized into:

- `domain`: notes, relay config, session, sync state.
- `nostr`: NIP-19 decoding, NIP-44 status, event model, crypto/client/repository interfaces.
- `data`: in-memory note store, relay settings, profile cache, secure key-store abstraction.
- `sync`: save, delete/tombstone, sync reduction, and relay migration planning.
- `ui`: shared Compose screens for login, list, display, edit, and relay settings.
- `util`: URL detection, npub detection, markdown helpers, relay URL validation, payload JSON codec.

Platform code:

- `androidMain`: Android activity and manifest.
- `desktopMain`: Compose Desktop window entry point.

## Known Limitations And TODOs

- Keep the production crypto adapter covered by offline generated-key tests before expanding runtime relay sync.
- Wire the bounded WebSocket relay client into explicit developer/test runtime paths, then production sync after storage and failure handling are reviewed.
- Add encrypted local cache storage. Do not persist private keys until platform secure storage exists.
- Implement Android encrypted key storage and decide on a Linux desktop secret-service integration.
- Fetch and cache kind 0 profile metadata once networking exists.
- Replace minimal markdown rendering with a compatible renderer if one fits licensing and KMP constraints.
- Add inline image/video loading with size limits and timeouts.
- Add real relay migration execution after relay read/write support exists.

## License

Other Note is licensed under the GNU General Public License version 3. See `LICENSE`.
