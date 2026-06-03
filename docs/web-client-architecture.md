# Web client architecture plan

This document is a design plan for a future Other Note web client. The web client is not implemented yet. Android and Debian/Linux desktop remain the active tested targets.

The first web client should be a fallback for users who cannot yet use a native Android, Linux, Windows, macOS, or iOS client. It must preserve the native app's core security model: signing, encryption, decryption, note reduction, and Markdown rendering happen on the client side.

## Non-negotiable security rules

- MUST keep Nostr signing client-side.
- MUST keep note encryption client-side.
- MUST keep note decryption client-side.
- MUST NOT send plaintext notes to a server.
- MUST NOT send `nsec` values or private keys to a server.
- MUST NOT store `nsec` values or private keys in `localStorage`.
- MUST NOT store `nsec` values or private keys in IndexedDB.
- MUST NOT store `nsec` values or private keys in cookies.
- MUST NOT store `nsec` values or private keys in server sessions.
- MUST NOT log `nsec` values, private keys, bunker tokens, signer secrets, decrypted notes, decrypted payload JSON, or raw decrypted NIP-44 payloads.
- MUST NOT add analytics or telemetry that can capture note text, keys, signer payloads, relay payloads, account secrets, or sensitive diagnostics.
- MUST treat direct `nsec` paste as session-only unless a future explicitly approved design changes that.
- MUST prefer NIP-07 browser extensions and NIP-46 remote signers for normal web sign-in.
- MUST keep NIP-46 signer transport relays separate from note write/fetch relays.

## Supported web sign-in strategy

Recommended priority:

1. Phase 1: NIP-07 browser extension signer.
2. Phase 1 or 2: NIP-46 remote signer/bunker.
3. Fallback: direct `nsec` paste for the current browser session only.

Explicitly unsupported initially:

- Storing a user `nsec` or private key in browser durable storage.
- Creating a web-managed saved `nsec` identity.
- Server-side auth sessions for encrypted notes.
- Android NIP-55. NIP-55 is Android-native signer delegation and does not apply to a static web app.

UX requirements for the first web sign-in surface:

- Detect whether `window.nostr` is available before offering NIP-07 as the recommended action.
- Explain clearly when no extension signer is available.
- Offer a remote signer/bunker option where feasible.
- Keep direct `nsec` paste lower emphasis and session-only.
- Clear direct `nsec` input after successful login or logout.
- Ensure logout clears session-only secrets and authenticated in-memory state.

## Client-side cryptography boundary

For NIP-07, the browser extension owns the private key and performs signing. Any NIP-44 encrypt/decrypt support must use the extension only if the extension exposes the needed APIs safely; otherwise the web client must not pretend that encrypted note operations are available through that signer.

For NIP-46, the remote signer owns the user's private key. Other Note may use a local NIP-46 communication key for encrypted kind `24133` request/response traffic, but web persistence of that communication key is not approved by default. The remote signer may see plaintext note payloads during signer-mediated NIP-44 encrypt/decrypt operations by design.

For direct `nsec` fallback, the private key may exist only in browser memory for the active session. It may be used to sign events and perform NIP-44 encrypt/decrypt locally, then must be discarded on logout, refresh, tab close, or process end as far as browser behavior allows.

Decrypted note text may exist in browser memory and UI state only. It must not be sent to an Other Note server, written to logs, stored in durable browser storage, or captured by analytics.

The web implementation should reuse existing note event and reducer semantics where practical: kind `30078`, the `other-note:note:<note_id>` `d` tag, encrypted note payload schema, latest-event/tombstone materialization, and display-only Markdown rendering.

## Relay communication strategy

The first web design should connect directly from the browser to public Nostr relays over WebSocket where possible. Do not use an Other Note relay proxy for normal operation in the first implementation.

Expected relay risks:

- Some relays may not behave consistently with browser WebSocket clients.
- Browser connection limits and mobile browser lifecycle behavior may interrupt long syncs.
- Public relays may purge old events or reject writes.
- Error surfaces must distinguish note relay fetch/publish failures from signer transport failures.

Relay-list behavior should mirror native semantics where feasible:

- Note relay settings manage relays used to fetch and publish encrypted kind `30078` Other Note events.
- Public kind `10002` relay-list import/publish should preserve unrelated relay categories where practical.
- NIP-46 signer transport relays carry encrypted kind `24133` app/signer traffic and must remain separate from note relays.
- The note relay settings screen must not show, edit, import, or publish NIP-46 signer transport relays.

## Storage policy

Initial web storage should be conservative. The first implementation should prefer in-memory state plus non-secret preferences only.

Allowed browser storage, if needed:

- Non-secret UI preferences such as selected theme.
- Non-secret note list preferences such as selected sort option.
- Non-secret local note relay preferences, if the user explicitly edits them.
- Encrypted cached Nostr events only after a later design approves cache scope, clearing behavior, and XSS risk.

Forbidden browser storage:

- `nsec` values or private keys.
- NIP-46 client communication private keys unless a future explicit secure web persistence design approves them.
- Bunker pairing secrets.
- Signer secrets or tokens.
- Decrypted note body text.
- Decrypted payload JSON.
- Raw decrypted NIP-44 payloads.
- Raw sensitive diagnostics.

Direct `nsec` fallback must not write the key to `localStorage`, IndexedDB, cookies, Cache Storage, server sessions, analytics, crash reports, or logs. If browser durable storage is introduced later for non-secret data, it must be audited so secrets cannot be accidentally routed into it.

## Deployment model

Use a static site or PWA-style deployment model with no server-side note processing.

Deployment requirements:

- HTTPS only.
- Strong Content Security Policy.
- No third-party analytics.
- No third-party script injection.
- No server-side signing, encryption, decryption, or plaintext note processing.
- No server logs containing note text, `nsec` values, private keys, bunker tokens, signer payloads, or sensitive relay payloads.
- Immutable/static assets where practical.
- Service worker support only after a separate cache/security design is reviewed.

Served JavaScript has a larger trust surface than native binaries because the code can change at deploy time. Any public web deployment must treat build provenance, asset integrity, CSP, and dependency review as release blockers.

## Code sharing plan

Likely shared common code:

- Note model and encrypted payload schema.
- Nostr event model and serialization helpers.
- Note event reducer and tombstone materialization.
- Relay URL validation and note relay settings rules.
- Markdown parser/renderer model for the practical supported subset.
- Local note search and note list sort/filter helpers.
- Theme definitions.
- Profile metadata parsing.
- NIP-46 protocol payloads and token parsing where browser-compatible.

Likely web-specific code:

- NIP-07 signer adapter for `window.nostr`.
- Browser WebSocket relay client if the Android OkHttp and desktop JVM clients cannot be reused.
- Browser storage adapter for non-secret preferences.
- Browser platform service factory.
- Browser clipboard/link handling.
- Browser-safe diagnostics and error copy.
- Static hosting, CSP, and deployment configuration.

Known unknowns:

- Kotlin/Wasm versus Kotlin/JS target choice.
- Compose Multiplatform web maturity for the current UI.
- Quartz and other crypto dependency compatibility in the browser.
- Browser WebSocket behavior across Chromium, Firefox, mobile browsers, and privacy modes.
- Whether NIP-07 extensions expose enough NIP-44 support for encrypted notes.
- Whether web NIP-46 session persistence can be designed safely without durable bearer secrets.

## First implementation milestone proposal

Future implementation branches should stay narrow:

- `web-client-skeleton`
  - Add a web target/build shell.
  - Render a static app shell.
  - Add no secret storage.
  - Add no note sync yet.
- `web-client-nip07-signin`
  - Detect `window.nostr`.
  - Request the account pubkey.
  - Establish safe in-memory signed-in state.
  - Add no note writes until signer capabilities are verified.
- `web-client-nip46-signin`
  - Add remote signer token parsing and request flow if browser relay transport is ready.
  - Keep the session non-persistent initially unless a safe persistence design is approved.
- `web-client-note-read-only`
  - Fetch encrypted note events from note relays.
  - Decrypt through the selected signer path where supported.
  - Reuse native reducer and Markdown display behavior.
- `web-client-note-crud`
  - Create, edit, and tombstone notes using existing kind `30078` event semantics.
  - Publish only signed encrypted events to note relays.
  - Keep pending write behavior bounded and visible.

## Risks and open questions

- Browser XSS risk can expose in-memory keys, decrypted notes, signer payloads, and relay payloads.
- CSP must be strict enough to block script injection and accidental third-party code execution.
- Service worker caching can preserve stale vulnerable code or sensitive artifacts if designed poorly.
- Browser durable storage has no OS keychain-equivalent security boundary.
- NIP-07 extension compatibility varies, especially for NIP-44 encrypt/decrypt.
- NIP-46 web persistence is a bearer-capability problem and needs a separate design before durable storage.
- Crypto library compatibility and randomness quality must be verified in the chosen browser target.
- Browser WebSocket relay behavior may differ from Android and JVM clients.
- Large note sets may need careful incremental fetch/reduction to avoid blocking the UI.
- Mobile browser lifecycle behavior may interrupt sync, pending writes, and signer prompts.
- iOS browser limitations may affect extension support and storage behavior.
- Local-only mode on web needs a separate decision because browser refreshes can destroy in-memory notes and durable local storage may create privacy risks.

## Web implementation acceptance checklist

Future web implementation branches must verify:

- [ ] No user `nsec` or private key is written to durable browser storage.
- [ ] No plaintext note or decrypted payload is sent to a server.
- [ ] No analytics or telemetry can capture secrets, note text, signer payloads, or relay payloads.
- [ ] CSP is reviewed before any public deployment.
- [ ] NIP-07 sign-in path is tested where implemented.
- [ ] NIP-46 path is tested where implemented.
- [ ] Direct `nsec` fallback is session-only.
- [ ] Logout clears memory secrets and authenticated in-memory state.
- [ ] Relay settings remain note-relay-only.
- [ ] NIP-46 signer transport relays remain separate from note relays.
- [ ] Note reducer behavior matches native latest-event/tombstone semantics.
- [ ] Markdown rendering, local search, sort/filter, and theme behavior match native where practical.
- [ ] No raw secrets, decrypted notes, raw signer payloads, or sensitive diagnostics appear in UI or logs.
- [ ] Manual browser checks cover at least Chromium and Firefox when practical.

## Relation to native clients

Android and Debian/Linux remain the primary active native targets for now. The future web client should fill access gaps for users without a native client, especially before Windows, macOS, and iOS native targets are available.

The web client must not weaken the native app security model. It should reuse shared behavior where that reduces drift, but it should remain conservative about browser-specific risk, especially around durable storage, served JavaScript, third-party scripts, CSP, and signer compatibility.
