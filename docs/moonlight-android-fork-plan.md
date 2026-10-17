# Plan: a Moonlight Android TV fork for StrmQt

A brief for an agent working in a **separate repository** (a fork of
`moonlight-stream/moonlight-android`). It needs no access to the StrmQt
source; everything it must know about StrmQt is in this file.

Written 2026-10-13, updated 2026-10-16. Statements about StrmQt were read
from its source at `main` (`a5a56b4`). Statements about Moonlight's internals are from memory of
that project and are marked **[verify]**: confirm them against the code before
relying on them.

## 1. Goal

StrmQt is a native Emby client (Qt 6, Linux) that runs on a mini PC and is
streamed with Sunshine to Amazon Fire TV devices. Today the viewer needs a
second remote paired to the mini PC, because the stock Moonlight client does
not deliver the Fire TV remote's buttons to StrmQt in a usable form.

Build a Moonlight Android fork, sideloaded on Fire TV, that makes the Fire TV
behave like a StrmQt appliance:

1. **The Fire TV remote drives StrmQt.** D-pad, Select, Back, Menu,
   Play/Pause, Rewind and Fast-forward all do the expected thing.
2. **It connects by itself.** Opening the app goes straight to the StrmQt
   stream on the configured host, with no host list and no app grid, and
   reconnects if the stream drops.
3. **It can start with the device.** Optional, off by default: launch on boot
   and return to the stream after the device wakes.

Out of scope: any change to Sunshine (it stays stock), game streaming
features, publishing to an app store, phones and tablets.

## 2. The host side, as it will be

- Host: CachyOS (Arch), Ryzen 7 6800H with Radeon integrated graphics.
- A stock, recent Sunshine instance dedicated to StrmQt, capturing a headless
  3840x2160 screen that shows only StrmQt. Target stream: 4K, 30 fps, HEVC.
- This instance is separate from any desktop Sunshine on the same machine, so
  **its ports are not the defaults**. The client must let the user set the
  host address and port, not assume 47989.
- The headless StrmQt runs its own **Web Remote** server: HTTPS on port 8338,
  always on, self-signed certificate, one-time code pairing. This is the control
  channel described in section 4. (The desktop StrmQt's remote, for phones, is
  on 8337; a StrmLight does not use it.)
- **Discovery:** the headless StrmQt answers the UDP datagram
  `STRMQT-DISCOVER 1` sent to port 8338 (broadcast) with JSON
  `{"service":"strmqt","version":1,"name","remotePort","sunshinePort","fingerprint"}`
  to the sender. The reply's source address is the host. Only private,
  link-local and loopback senders are answered, one reply per sender per
  200 ms.

### Why not just send keyboard keys through Moonlight

Sunshine injects keyboard and mouse input as kernel virtual devices. The
headless screen StrmQt runs on does not read kernel input devices, so keys
sent the normal Moonlight way do not reach StrmQt. This was established by a
spike on 2026-10-13; a real Moonlight connection was not part of that spike.

Game controllers are different: StrmQt reads controllers directly (SDL3), so
a virtual gamepad created by Sunshine should reach it. That was not tested
either. It is the fallback transport in section 4.

## 3. What the Fire TV remote can give an app

Delivered to the foreground app as `KeyEvent`s: `DPAD_UP/DOWN/LEFT/RIGHT`,
`DPAD_CENTER`, `BACK`, `MENU`, `MEDIA_PLAY_PAUSE`, `MEDIA_REWIND`,
`MEDIA_FAST_FORWARD`. Some remotes add channel, guide or settings keys;
treat them as optional.

Never delivered: Home, the voice button, the app-shortcut buttons. Volume,
mute and power go to the TV or receiver by IR/CEC, not to the app.

Log the actual key codes and source device of the target remotes in phase 0
rather than trusting this list.

## 4. Remote transport

Make the transport an interface with two implementations, chosen in
settings. Build A first.

### A. StrmQt Web Remote (primary)

The client talks to StrmQt directly over the LAN. It does not depend on
Sunshine's input path at all.

- Base URL: `https://<host>:8338` (from discovery; port configurable).
- TLS: the certificate is self-signed. **Pin it on first use**: store the
  certificate's SHA-256 at pairing and accept only that certificate
  afterwards. Do not disable certificate checking.
- StrmQt replaces the certificate, **with a new key**, when the host gains an
  IPv4 address or host name the old one did not name (a new DHCP lease, a
  second network). A pinned client then sees a different certificate. Treat
  that as "pair again": stop, tell the user the host's certificate changed,
  and offer the pairing flow, which pins the new one. Never accept it
  silently, and never fall back to unchecked TLS. A fixed address for the
  host (a DHCP reservation) avoids this in practice.
- First, `GET /api/auth/pin`. `{"required": false}` means pairing is off on
  this host: use the API with no token. Otherwise pair.
- Pairing: there is no standing PIN. **The client makes up a random 4-digit
  code and shows it on its own screen**, with a line asking the user to type
  it into StrmQt (Settings, Web Remote, "Pair a StrmLight") or into the phone
  remote (Remote, "Pair a StrmLight"). **The same code is its Moonlight PIN:**
  it starts Moonlight's pairing against `<host>:<sunshinePort>` with it, and
  StrmQt enters it into that Sunshine once it is typed in, so one entry pairs
  the stream and the remote. Sunshine answers `409` while an abandoned pairing
  from any Moonlight is open (they all use one client id); retry every 5 s.
  Meanwhile, every 2 seconds, it sends
  `POST /api/auth/pin` with the header `X-StrmLight: <device name>` (up to 40
  characters, shown in StrmQt's list of paired devices) and JSON
  `{"pin": "<the code>"}`:
  - `202 {"pending": true}`: nobody has typed a code in yet. Keep asking.
    These are not counted as wrong guesses.
  - `200 {"token": "<token>"}`: paired. Store the token in app-private
    storage and stop showing the code.
  - `403`: a code was typed in but it is not this one (a typo). Keep the
    same code on screen and keep asking: asking again with the same code is
    counted as one wrong try, not one per poll, so the user can retype it.
    StrmQt drops a typed code after five different wrong codes, and the
    client is back to `202`.
  - `429`: too many wrong tries from this address, or asking too often.
    Wait 30 s, then resume.
  StrmQt allows a StrmLight 50 tries in any 95 s from one address (one per
  1.9 s on average), waiting tries included. Polling every 2 s stays inside
  that; never poll faster.
  The header is required: without it, the request is a phone's and is checked
  against the code StrmQt shows on its own screen instead. A code typed in for
  a StrmLight works once and expires after 2 minutes; make a fresh code when
  the user restarts pairing, not on every poll.
- **Tokens are kept until removed.** StrmQt stores a hash of each token and
  keeps it across restarts, sign-outs and profile switches, so a paired
  client stays paired. A `401` later means the user removed this device in
  StrmQt: delete the stored token and go back to pairing.
- Every other request carries `Authorization: Bearer <token>`.
- Send a button: `POST /api/action` with JSON `{"action": "<id>"}`. Unknown
  ids return `400`.
- `GET /api/status` returns current state (JSON); `GET /api/events` is a
  server-sent-events stream of state changes. Use these only if a feature
  needs them (for example, to know whether video is playing).

Action ids the remote needs (all exist in StrmQt today):

| Remote button | Action id | Notes |
|---|---|---|
| D-pad | `nav.up`, `nav.down`, `nav.left`, `nav.right` | Repeat while held (see below) |
| Select | `nav.select` | |
| Back | `nav.back` | |
| Menu | `app.toggleMenu` | Long press: `nav.contextMenu` |
| Play/Pause | `player.togglePause` | |
| Rewind | `player.seekBackward` | Long press: `player.seekBackwardLong` |
| Fast-forward | `player.seekForward` | Long press: `player.seekForwardLong` |

Other ids available if useful: `app.home`, `app.settings`, `player.stop`,
`player.next`, `player.previous`, `player.nextChapter`,
`player.previousChapter`, `player.toggleOsd`, `player.cycleAudio`,
`player.cycleSubtitle`, `nav.pageUp`, `nav.pageDown`.

Requirements for A:

- One kept-alive HTTPS connection, requests sent in order off the UI thread.
  A button press must not wait on a new TLS handshake.
- Key repeat is the client's job: actions are discrete, so a held D-pad
  sends the action again on a timer (suggested: 400 ms delay, then every
  90 ms), and stops on key-up or focus loss.
- If a request fails, drop queued repeats rather than replaying them late.

Known limits of A, to report rather than work around:

- StrmQt's YouTube and YouTube Music pages are embedded web pages. Whether
  every `nav.*` action reaches them the same way a key does has not been
  checked from this API.
- There is no text entry over this API.

Anything missing on the StrmQt side (a new action, a text-entry endpoint, a
longer-lived token) is a request to the StrmQt maintainer, not something to
emulate in the client.

### B. Gamepad buttons through Moonlight (fallback)

Present the remote to the existing controller path as a gamepad: D-pad as
D-pad, Select as A, Back as B, Menu as Start, Play/Pause as X, Rewind and
Fast-forward as the shoulder buttons. Sunshine creates a virtual controller
on the host and StrmQt reads it.

Moonlight may already do part of this for remotes **[verify]**. Phase 0
establishes what it does today; if stock behaviour already works with the
headless host, B is a settings default and no code.

## 5. Work, in order

### Phase 0: find out what happens today

No fork changes yet. With stock Moonlight on a Fire TV connected to the
host:

- Log every `KeyEvent` from the remote (key code, device id, source flags).
- Trace where Moonlight sends each one. Entry points to read **[verify]**:
  `com.limelight.Game` (the streaming activity; `handleKeyDown`/`handleKeyUp`),
  `com.limelight.binding.input.ControllerHandler`,
  `com.limelight.binding.input.KeyboardTranslator`.
- On the host, `sudo evtest` shows which virtual devices Sunshine creates and
  which events arrive.

Deliverable: a short table of button, what Moonlight sends, what arrives on
the host. This decides how much of B already exists.

### Phase 1: fork and build

- Fork `moonlight-stream/moonlight-android` (GPL-3.0). It uses a git
  submodule for the streaming core and the Android NDK **[verify]**.
- New application id and name so it installs beside stock Moonlight. Keep
  all upstream copyright and licence notices; add a NOTICE describing the
  fork. The fork stays GPL-3.0 with public source.
- Produce a debug build that streams from the host unchanged, sideloaded
  with `adb install`.
- Keep the diff small and upstream-mergeable: new behaviour in new classes
  behind settings, minimal edits to upstream files.

### Phase 2: remote mode with transport A

- A "StrmQt remote" setting group: on/off, StrmQt address and port, transport
  A or B, and "Pair again" (forgets the token).
- Pairing flow: fetch and pin the certificate, show a random 4-digit code and
  poll as in section 4A until a token comes back, then store the token and
  the certificate hash in app-private storage. No text entry on the Fire TV.
- In the streaming activity, when remote mode is on and the event comes from
  a remote (not a game controller or a real keyboard), consume it and send
  the mapped action instead of passing it to the normal input path. Real
  controllers and keyboards keep working as upstream.
- An exit gesture that cannot be triggered by normal use, since Back now
  belongs to StrmQt: hold Back for 2 seconds to open the fork's own menu
  (disconnect, settings). Show a one-time hint.
- A visible, non-blocking indicator when the control channel is down.

### Phase 3: appliance behaviour

- "Connect on launch": a configured host, port and app name; the launcher
  activity goes straight to the stream. Moonlight has a shortcut path that
  starts a stream from an intent **[verify]** (`ShortcutTrampoline`); reuse
  it rather than writing a second connection flow.
- Reconnect with backoff when the stream ends unexpectedly; give up to the
  fork's menu after a bounded number of tries. Use Moonlight's Wake-on-LAN
  if the host is asleep.
- Stream preset for this use: 3840x2160, 30 fps, HEVC, a bitrate setting,
  frame pacing suited to video. Fall back to 1080p when the device cannot
  decode 4K.
- Keep the screen on while streaming.

### Phase 4: start with the device (optional, off by default)

This is the fragile part. Fire OS does not allow replacing the launcher and
restricts starting an activity from the background.

- A `BOOT_COMPLETED` receiver that starts the stream. On current Fire OS
  this needs the "display over other apps" permission, granted once with
  `adb shell appops set <package> SYSTEM_ALERT_WINDOW allow` **[verify on
  the target Fire OS version]**.
- "Return on wake": bring the stream back when the device wakes, by the same
  mechanism.
- Screensaver and sleep are device settings changed over `adb`; document the
  commands, do not attempt them from the app.
- Document clearly that an Amazon update can break this, and that the manual
  path (open the app) always works.

## 6. Acceptance

On a Fire TV Stick 4K class device, with the headless host running:

1. Fresh install, pair with Sunshine, pair with StrmQt by typing the code the
   app shows into StrmQt or the phone remote, without a keyboard on the Fire
   TV. Restarting StrmQt or the app does not ask for pairing again; removing
   the device in StrmQt does.
2. Every button in the section 4 table does what the table says, on the
   StrmQt home screen and during video playback.
3. Holding a D-pad direction scrolls steadily and stops on release, with no
   run-on after the button is let go.
4. Restarting StrmQt on the host: the remote recovers by itself within a few
   seconds.
5. Closing and reopening the app lands in the stream with no navigation.
6. A real game controller paired to the Fire TV still works as in upstream.
7. Hold-Back opens the fork's menu every time and is never triggered by a
   short press.
8. With phase 4 enabled and the `adb` permission granted: power-cycling the
   Fire TV ends in the stream.

Report for each item whether it was tested on a real device or only in an
emulator. An Android TV emulator cannot stand in for the Fire TV remote or
for Fire OS boot behaviour.

## 7. Risks to raise early

- Phase 0 may show stock Moonlight already delivers the remote as a gamepad
  and StrmQt handles it. Then phases 2A and 3 are still worth building, but
  say so before building them.
- 4K HEVC at 30 fps decode varies by Fire TV model. Record the model and
  measured decode time.
- Input latency of transport A depends on the LAN, not on the video stream.
  Measure button-to-screen time and report it.
- Wrong-code lockouts on the StrmQt side during development: honour `429`
  instead of retrying in a loop. Waiting polls (`202`) are never counted, but
  wrong codes are. Besides each address's own lockout, StrmQt has one
  allowance shared by every address that has never paired (20 misses, then
  one per 5 minutes), so a misbehaving test client can also hold off a first
  pairing from the real device. A device that has paired is held only to its
  own lockout.
