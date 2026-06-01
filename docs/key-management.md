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
2. Session-only pasted `nsec`.
3. Explicit saved-device `nsec` only through OS-backed credential storage.
4. No plaintext persistence fallback.

Session-only `nsec` means the key is held only in process memory for the active app session. The input field must be cleared after login and the full `nsec` must not be displayed after entry.

## Android Plan

Preferred Android signing is external signer delegation through NIP-55 Android signer apps such as Amber. NIP-46 remote signer/bunker support is also planned for later.

Direct `nsec` paste may exist as a fallback. The direct `nsec` field should use password/autofill/credential behavior so Android and Google Password Manager can prompt where appropriate. The app itself must not save Android `nsec` values to plaintext app storage.

Saved-device `nsec` support must require Android secure credential or keystore-backed storage when implemented. Until that exists, saved-key mode stays disabled and session-only mode is used.

## Desktop Plan

Saved-device key storage may be enabled only through OS credential stores:

- Debian/Linux: Secret Service/libsecret or an equivalent OS keyring.
- Windows: Windows Credential Manager, optionally gated by Windows Hello or user presence where feasible.
- macOS: macOS Keychain.

If an OS keyring is unavailable, locked, unsupported, or not implemented, saved-key mode must be disabled and session-only mode used. There must be no plaintext file fallback.

The desktop developer relay runtime may cache signed encrypted Nostr events and pending relay-write metadata under the user's local app data directory. That cache is not key storage: it must not contain `nsec` values, private keys, decrypted note bodies, decrypted payload JSON, or NIP-44 plaintext. It may contain encrypted event content because that is the same signed ciphertext intended for public relays.

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

This pass does not implement:

- OS keyring storage.
- Android NIP-55 signer integration.
- NIP-46 remote signer/bunker support.
- NIP-07 web signer support.
- A web app.
- Production OS keyring-backed saved-key mode.

Secure storage implementations must be added platform by platform and tested before saved-key mode is enabled. A test fake may exist only in tests and must be clearly marked as a test fake.

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
