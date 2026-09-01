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
- Saved multi-Mac registry with one-tap switching between connected hosts
- Live connection pool with per-Mac status and explicit connect-all action
- Optional foreground background monitor with machine-aware alerts

The Iroh JNI libraries are built from cmux's pinned `manaflow-ai/iroh-ffi`
fork (`v1.0.2-cmux.7`) for `arm64-v8a` and `x86_64`.

## Use

1. Install the APK and sign in with the same cmux account as the Mac.
2. Open **Mobile Connect** in cmux on the Mac.
3. Tap **Find my Macs**, scan its QR code, or paste the pairing link.

No Claude API traffic originates on Android: Claude Code continues running on
the Mac, so the Mac's existing proxy and credentials remain authoritative.

## Multi-Mac control

Use **Find my Macs** to discover every pairable Mac on the signed-in cmux
account. Each selected Mac is saved locally on the phone and can be reopened
locally from the **Mac:** switcher in the workspace screen. Manual Tailscale
hosts are saved too. Android keeps connected hosts alive while the app is open;
the selected Mac owns the active terminal view. Each Mac remains authoritative
for its own workspaces and terminal state.

The saved registry contains only display and route metadata. Iroh identities and
account credentials remain in the Android Keystore-backed store.

Enable **Monitor Macs in background** in Settings to keep subscriptions alive
for saved Macs after the Activity is closed. Android shows one ongoing monitor
notification plus a per-Mac alert when unread agent activity appears. Disable
the setting to stop the foreground monitor.

For a private direct TCP route, see [`relay/README.md`](relay/README.md) for the
tailnet-only Mac relay helper.

## Build

```sh
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

The installable debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. This project is
GPL-3.0-or-later, matching cmux.
