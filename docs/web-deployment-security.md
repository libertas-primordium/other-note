# Web deployment security

This document is the deployment/security checklist for the static Other Note web preview. It is not a public release certification. The web preview is functional enough to sign in with NIP-07, NIP-46, explicit remembered NIP-46 remote-signer reconnect, or a lower-emphasis session-only direct `nsec` fallback; create a fresh identity for the current browser session; select a built-in visual theme; display text-only profile metadata; load encrypted notes; search/sort the currently loaded in-memory note list; create/edit/delete notes; and choose session-only note relays, but it remains security-sensitive and intentionally memory-only except for the documented theme and remembered NIP-46 storage records.

Use this document with [web-client-architecture.md](web-client-architecture.md) and [key-management.md](key-management.md).

## Static Hosting Requirements

- Serve the production distribution over HTTPS only.
- Serve static assets only.
- Do not add server-side note processing.
- Do not add server-side signing, encryption, decryption, or plaintext note handling.
- Do not add analytics, telemetry, crash reporting, remote logging, trackers, third-party scripts, or remote fonts.
- Do not add a service worker or offline cache unless a later reviewed cache/security design explicitly approves it.
- Do not log request bodies, signer payloads, relay payloads, note plaintext, `nsec` values, bunker tokens, or other account secrets.
- Web fonts must be same-origin static assets. The current web preview bundles Roboto WOFF2 files under `fonts/roboto/` with Apache-2.0 attribution; do not replace them with Google Fonts, CDN CSS, remote font URLs, or `local(...)`-only font loading.

## Production Security Headers

Production deployments must set CSP and related controls as HTTP response headers from the static host. Do not rely on a CSP meta tag in `index.html`: it cannot enforce every deployment control, including `frame-ancestors`, and it can break the Kotlin/JS webpack development server.

Recommended production headers:

```text
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; connect-src 'self' wss:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'; worker-src 'none'; upgrade-insecure-requests
Referrer-Policy: no-referrer
X-Content-Type-Options: nosniff
Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=()
Cross-Origin-Opener-Policy: same-origin
```

The current static shell has inline CSS, so the CSP includes `style-src 'self' 'unsafe-inline'`. A later styling cleanup can move CSS into a separate bundled file and remove `'unsafe-inline'` from `style-src`.

`connect-src 'self' wss:` is a conscious tradeoff. Users can select arbitrary Nostr note relays, and NIP-46 signer transport also uses WebSocket relays. The web client still keeps signing, encryption, and decryption client-side or signer-delegated; relay traffic is expected encrypted Nostr traffic, not server-side note processing.

Do not copy local `ws://` development allowances into a production HTTPS deployment unless a reviewed local-development deployment explicitly needs them. Do not use invalid bracketed IPv6 wildcard CSP sources.

## Local Development CSP

The command below uses webpack dev server:

```bash
./gradlew :web:jsBrowserDevelopmentRun
```

Webpack dev server may use eval-like development code and local WebSocket connections for live reload. A strict production CSP in `index.html` can therefore blank the local page with an `unsafe-eval` violation before Other Note code runs. The source `index.html` intentionally does not include a CSP meta tag; production CSP must be tested through the final static host or a local production-like server that sends HTTP headers.

If a temporary development CSP is needed for local experiments, keep it separate from the production header template. Development-only allowances may include loopback WebSocket sources such as `ws://localhost:*` or `ws://127.0.0.1:*`. Production should continue to use `connect-src 'self' wss:` for Nostr relay traffic.

## Browser Storage Policy

Current web auth/session/note/relay state is memory-only except for two explicit storage records:

- `on.web.theme`: a generic selected theme ID.
- `on.web.nip46`: an explicit opt-in remembered NIP-46 remote-signer communication-session record.

- Auth state is not durably restored after refresh.
- NIP-46 sign-in remains session-only by default. If the user opts in, the remembered NIP-46 record may store only version, returned user pubkey, local NIP-46 communication private key, communication pubkey, remote signer pubkey, signer transport relay URLs, and timestamps. It must not store the original bunker token secret, user private key, direct `nsec`, generated identity key, note data, note relay settings, relay stats, profile data, search/sort state, or pending writes.
- Direct-key `nsec` sessions are exposed only as session-only fallback paths. Pasted keys are not saved, generated keys are shown only in the explicit acknowledgement flow, direct-key drafts are cleared on submit/cancel/session replacement, and refresh/logout forgets the session.
- Decrypted note bodies and decrypted payload JSON are not persisted.
- Profile metadata is not persisted. The signed-in account header may render the active account's supported HTTPS profile `picture` as a small thumbnail with a same-origin placeholder fallback; remote `banner` URLs remain inert.
- Full-note view may render user-authored HTTPS inline image URLs from note content. Cards, previews, and editors must not prefetch or render remote images.
- Drafts, pending writes, note relay preferences, relay stats, search/sort state, and loaded note events are not persisted.

Forbidden for auth/session/key/note/draft/pending-write data:

- `localStorage`, except for the exact generic theme preference key `on.web.theme` and explicit remembered NIP-46 key `on.web.nip46`
- IndexedDB
- cookies
- Cache Storage
- service workers
- server sessions
- analytics, telemetry, crash reports, or remote logs

## Build And Inspection

Build the static distribution:

```bash
./gradlew :web:jsBrowserDistribution
```

Expected output:

```text
web/build/dist/js/productionExecutable/
```

Before deployment, inspect that directory:

- [ ] Contains `index.html`.
- [ ] Contains the generated `other-note-web.js` bundle.
- [ ] Contains the self-hosted `fonts/roboto/*.woff2` files and Roboto license/attribution files.
- [ ] Contains no service worker files.
- [ ] Contains no unexpected third-party scripts.
- [ ] Contains no generated files staged in git.
- [ ] Source maps are handled according to deployment policy. If source maps are public, assume users can inspect implementation details.

## Manual Web Smoke Checklist

Run the development server for local checks:

```bash
./gradlew :web:jsBrowserDevelopmentRun
```

Manual checks:

- [ ] Open the app in Chromium.
- [ ] Open the app in Firefox if practical.
- [ ] Confirm NIP-07 sign-in works when a compatible extension is available.
- [ ] Confirm NIP-46 sign-in works with a real remote signer.
- [ ] Load notes.
- [ ] Confirm active-account profile text appears when available, and no remote profile images are fetched or rendered.
- [ ] Create a note.
- [ ] Edit the note.
- [ ] Delete/tombstone the note.
- [ ] Add a session-only note relay and confirm reload/publish still use note relays.
- [ ] Confirm NIP-46 signer transport relays are not shown as note relays.
- [ ] Restore default note relays.
- [ ] Log out and confirm auth/note/draft state clears.
- [ ] Refresh and confirm auth and notes are not durably restored.

DevTools storage checks:

- [ ] `localStorage` contains no Other Note auth/session/key/note data except the allowed `on.web.theme` value and explicit `on.web.nip46` remembered remote-signer record if the user opted in.
- [ ] If `on.web.nip46` exists, it contains no direct `nsec`, generated identity key, user private key, bunker token secret, note content, note events, note relay settings, relay stats, profile data, search query, or sort preference.
- [ ] No Other Note auth/session/key/note data in IndexedDB.
- [ ] No Other Note auth/session/key/note data in cookies.
- [ ] No Other Note Cache Storage entries.
- [ ] No service worker registered.

DevTools network checks:

- [ ] Static assets load from the web host.
- [ ] Nostr relay WebSockets are expected note relay or signer transport connections.
- [ ] No analytics or telemetry requests.
- [ ] No backend note API calls.
- [ ] No unexpected third-party script, font, image, or tracking requests.
- [ ] Font requests are same-origin `fonts/roboto/*.woff2` requests only.
- [ ] Remote profile `picture` requests occur only for the active signed-in account header thumbnail, and unsupported/missing/failing images fall back to the same-origin placeholder.
- [ ] No remote profile `banner` image requests.
- [ ] Remote note image requests occur only after opening full-note view and only for supported HTTPS image URLs.

## CSP Smoke Checklist

When testing a production-like host with headers:

- [ ] Confirm the host sends the production CSP header.
- [ ] Confirm required Nostr relay WebSockets are not blocked by CSP.
- [ ] Confirm the app has no normal-use CSP violations.
- [ ] Confirm unexpected scripts are blocked.
- [ ] Confirm object/embed loads are blocked.
- [ ] Confirm framing is blocked by `frame-ancestors 'none'`.

## Known Limits

- No durable web sessions except explicit opt-in remembered NIP-46 remote-signer communication sessions.
- Direct `nsec` web flow is session-only and not remembered after refresh/logout.
- No service worker or offline mode.
- No durable web note cache.
- No persistent pending-write queue.
- No durable web relay preferences.
- Web kind `10002` relay-list import/publish, relay-change migration, and manual Sync/Migrate are session-only and best-effort; there is no durable migration queue or offline relay migration.
- User-selected relay URLs require broad `wss:` connection allowance.
