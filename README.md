# cmux for Android

Native Android companion for cmux `v0.64.22`, ported against the matching
public iOS client and Mac host protocol.

## Features

- Same-account email OTP sign-in with Android Keystore-backed credentials
- Exact Iroh v3 pairing (`cmux-ios://attach?v=3&i=…`), broker discovery,
  managed relays, saved identity, startup restore, and reconnect
- Tailscale/manual TCP pairing compatibility
- State-sync v2 workspaces, groups, search, create/task composer, directory
  browser, rename, pin, read state, metadata, colors, move, and close
- Screen-anchored terminal mirror with up to 20,000 local scrollback rows,
  stable redraws for Claude Code/TUIs, cursor/styles/theme, touch and hardware
  input, image paste, files, and configurable text size
- Claude Code/Codex chat sessions, history, rich event cards, questions,
  permissions, image attachments, interrupt, and artifact browsing
- Remote cmux browser streaming plus a native on-device browser
- Agent activity feed, Android notifications, deep links, reconcile/read/dismiss
- Workspace changes, diffs, current/base file preview, and file export

The Iroh JNI libraries are built from cmux's pinned `manaflow-ai/iroh-ffi`
fork (`v1.0.2-cmux.7`) for `arm64-v8a` and `x86_64`.

## Use

1. Install the APK and sign in with the same cmux account as the Mac.
2. Open **Mobile Connect** in cmux on the Mac.
3. Tap **Find my Macs**, scan its QR code, or paste the pairing link.

No Claude API traffic originates on Android: Claude Code continues running on
the Mac, so the Mac's existing proxy and credentials remain authoritative.

## Build

```sh
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

The installable debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. This project is
GPL-3.0-or-later, matching cmux.
