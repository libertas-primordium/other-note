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
- Production Nostr crypto is not wired yet. The current `NonProductionNostrCrypto` validates NIP-19 `nsec` format but refuses secp256k1 signing and NIP-44 encryption/decryption so plaintext notes are not accidentally published.
- Relay networking is represented by clean interfaces and an offline adapter. Publishing and fetching surface explicit non-success status instead of fake success.
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

Edits publish a new signed event with the same kind/pubkey/d-tag. Relay results are grouped by d tag and note id; the newest event by `created_at` wins. If timestamps tie, the lexicographically larger event id wins. Deletion uses an app-level tombstone with `deleted=true` and an empty body so relay DELETE support is not required.

## Relay Retention

Default relays are editable:

- `wss://relay.damus.io`
- `wss://relay.primal.net`
- `wss://relay.nostr.net`

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

Relay integration tests are intentionally opt-in and currently stop with a clear failure until a production crypto adapter is wired:

```sh
OTHER_NOTE_RELAY_TESTS=1 OTHER_NOTE_TEST_RELAYS=wss://relay.example.com ./gradlew :app:desktopTest
```

Generated relay-test notes are disposable. If relay tests are enabled in the future, encrypted test blobs may remain on public relays.

## Crypto Adapter Status

The production crypto boundary is `NostrCrypto` plus `ProductionNostrCryptoFactory`.

Required production capabilities before real relay publishing:

- Throwaway private-key generation.
- NIP-19 `nsec`/`npub` encode/decode.
- Public-key derivation from private key.
- NIP-01 canonical event preimage, id hashing, Schnorr signing, and signature validation.
- NIP-44 v2 encryption/decryption to self.

Quartz was evaluated as the preferred Kotlin Multiplatform Nostr path because it is MIT-licensed and therefore GPLv3-compatible, and because it targets Nostr crypto across JVM/Android. Current Quartz artifacts resolve Kotlin 2.x metadata and transitive Kotlin 2.x standard libraries, while this project currently uses Kotlin 1.9.24 and Compose Multiplatform 1.6.11. To avoid destabilizing the build, Quartz is not integrated yet. The next crypto phase should either upgrade the Kotlin/Compose toolchain first or find a production Nostr crypto library that remains compatible with Kotlin 1.9.24.

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

- Integrate a GPL-compatible, audited Nostr crypto path for NIP-19, secp256k1 Schnorr signing, event id validation, and NIP-44 v2 encryption/decryption.
- Replace `OfflineNostrClient` with a real bounded WebSocket relay pool.
- Add encrypted local cache storage. Do not persist private keys until platform secure storage exists.
- Implement Android encrypted key storage and decide on a Linux desktop secret-service integration.
- Fetch and cache kind 0 profile metadata once networking exists.
- Replace minimal markdown rendering with a compatible renderer if one fits licensing and KMP constraints.
- Add inline image/video loading with size limits and timeouts.
- Add real relay migration execution after relay read/write support exists.

## License

Other Note is licensed under the GNU General Public License version 3. See `LICENSE`.
