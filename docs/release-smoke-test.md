# Release artifact smoke test

This checklist validates built Android and Debian/Linux artifacts, not only Gradle run and test paths. Installed packages can fail differently from `./gradlew :app:run` because the Debian package uses a custom `jpackage` runtime image instead of the full development JDK.

Use this before treating APK or `.deb` output as a release/test-candidate artifact.

## Preconditions

- [ ] Branch is up to date for the candidate being tested.
- [ ] Working tree is clean, or any changes are intentional candidate changes.
- [ ] Android SDK is installed for APK builds.
- [ ] Android device or emulator is available for Android manual checks.
- [ ] System Java 21 is available for Debian packaging.
- [ ] `dpkg-deb`, `apt`, and `xdg-desktop-menu` are available for Debian package inspection/install checks.
- [ ] No generated APKs, `.deb` files, logs, local key material, or screenshots are staged.

## Automated checks

Run from the repository root:

```bash
git diff --check
./gradlew :app:desktopTest
./gradlew :app:check
./gradlew :app:assembleDebug
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:packageDeb
git status --short
```

Expected generated artifact paths:

- Android debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Debian package: `app/build/compose/binaries/main/deb/other-note_0.1.0_amd64.deb`
- Web production distribution: `web/build/dist/js/productionExecutable/`

Generated artifacts must remain untracked.

## Android artifact smoke test

Build and install:

```bash
./gradlew :app:assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Manual checks:

- [ ] App installs and launches.
- [ ] Launcher label is `Other Note`.
- [ ] Launcher icon appears.
- [ ] Compact sign-in screen appears.
- [ ] Android NIP-55 restored session works if an active saved signer session exists.
- [ ] Existing active NIP-55 session restore does not open Amber only to continue the app session.
- [ ] First-time Android signer flow still works when needed.
- [ ] NIP-46 saved remote signer appears and resumes if available.
- [ ] Create, view, edit, and delete a note.
- [ ] Deleted note does not reappear after force close/reopen.
- [ ] Markdown renders in view mode for headings, fenced code blocks, bold, italic, strike, inline code, and blockquotes.
- [ ] Edit mode preserves raw Markdown text.
- [ ] Long-note editor remains scrollable with the keyboard open, and the cursor stays visible.
- [ ] Log out disables Android NIP-55 automatic restoration.
- [ ] Local-only mode works and does not sync to relays.
- [ ] `git status --short` does not show generated artifacts.

## Debian/Linux artifact smoke test

Build and inspect:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:packageDeb
dpkg-deb -c app/build/compose/binaries/main/deb/other-note_0.1.0_amd64.deb | grep -E 'Other Note|Other_Note|other-note|runtime|desktop|java.net.http'
dpkg-deb --fsys-tarfile app/build/compose/binaries/main/deb/other-note_0.1.0_amd64.deb | tar -xOf - './opt/other-note/lib/other-note-Other_Note.desktop'
```

Install or reinstall locally:

```bash
sudo apt install --reinstall ./app/build/compose/binaries/main/deb/other-note_0.1.0_amd64.deb
```

Run the installed app directly:

```bash
"/opt/other-note/bin/Other Note"
```

Verify the packaged runtime includes `java.desktop` for external link opening and `java.net.http` for the desktop relay client.

Preferred check when the packaged runtime exposes `bin/java`:

```bash
/opt/other-note/lib/runtime/bin/java --list-modules | grep -E '^(java.desktop|java.net.http)'
```

Fallback for current `jpackage` layouts that omit `runtime/bin/java`:

```bash
grep -E 'java.desktop|java.net.http' /opt/other-note/lib/runtime/release
```

Manual checks:

- [ ] Installed desktop can open full-note `http://` and `https://` links through the default browser; on Linux this should work through Java Desktop browse or the `xdg-open` fallback.
- [ ] Installed binary opens without `NoClassDefFoundError: java/net/http/HttpClient`.
- [ ] Desktop launcher entry appears as `Other Note`.
- [ ] Desktop icon appears instead of a blank/default Java icon.
- [ ] App opens from the launcher and from the terminal.
- [ ] Desktop sign-in screen appears.
- [ ] Desktop Secret Service/keyring saved identity sign-in works if available.
- [ ] NIP-46 saved remote signer resumes if available.
- [ ] Local-only note create/view/edit/delete smoke path works.
- [ ] Markdown view/edit behavior matches Android for the supported subset.
- [ ] `git status --short` does not show generated artifacts.

## Known regression checks

- [ ] Debian package does not fail with `NoClassDefFoundError: java/net/http/HttpClient`.
- [ ] Installed desktop app uses its packaged runtime successfully, not only the development JDK path.
- [ ] Android does not require fresh Amber approval just to restore an already active NIP-55 session.
- [ ] Sign-in screen remains compact and does not regress into inline documentation.
- [ ] Raw Markdown remains unchanged in edit mode after view-mode rendering.
- [ ] Deleted notes do not reappear after restart.
- [ ] Local-only mode does not sign, publish, or sync to relays.
- [ ] NIP-46 saved session resumes without a fresh pairing handshake.

## Web artifact smoke test

Build and inspect:

```bash
./gradlew :web:jsBrowserDistribution
find web/build/dist/js/productionExecutable -maxdepth 2 -type f -printf '%P\n' | sort
```

Manual/security checks:

- [ ] Production distribution contains `index.html` and the generated `other-note-web.js` bundle.
- [ ] Production distribution contains no service worker files.
- [ ] `index.html` loads only self-hosted static assets.
- [ ] `index.html` includes the static smoke-test CSP meta tag.
- [ ] Production hosting sends CSP/security headers; see [web-deployment-security.md](web-deployment-security.md).
- [ ] NIP-07 sign-in works where a compatible extension is available.
- [ ] NIP-46 sign-in works with a real remote signer if available.
- [ ] Note load/create/edit/delete and session-only note relay selection still work.
- [ ] DevTools storage contains no Other Note auth/session/key/note/draft/pending-write data.
- [ ] DevTools network shows only static assets, expected Nostr relay WebSockets, and expected signer transport behavior.
- [ ] `git status --short` does not show generated web artifacts.

## Cleanup

Optional uninstall:

```bash
sudo apt remove other-note
```

Final repository check:

```bash
git status --short
```

Do not commit generated APKs, `.deb` packages, logs, screenshots, local key material, `nsec` values, private keys, bunker tokens, or local credentials.

## Known limits

This is a smoke checklist, not full release certification.

Public release still needs real signing decisions, release APK/AAB policy, distribution policy, privacy/security review, web deployment/security review, and platform-specific work beyond the current Android and Debian/Linux targets.

Windows, macOS, iOS, and public web deployment are future targets and are not release-ready because this checklist passes.
