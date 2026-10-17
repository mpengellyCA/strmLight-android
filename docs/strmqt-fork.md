# StrmLight: Moonlight for StrmQt

A fork of Moonlight for Android that makes a Fire TV behave like a StrmQt
appliance. The brief it was built from is
[moonlight-android-fork-plan.md](moonlight-android-fork-plan.md); this file
records what was built, how to set it up, and what has and has not been
tested.

## Build and install

```
git submodule update --init --recursive
./gradlew :app:assembleNonRootDebug
adb install -r app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
./gradlew :app:testNonRootDebugUnitTest      # the fork's unit tests
```

The application id is `com.strmlight` (`com.strmlight.debug` for debug
builds), so it installs beside stock Moonlight. Gradle downloads the NDK and
SDK platform the build names if they are missing.

## Setup on the device

Open StrmLight, find StrmQt, type one code, stream. Everything else is
behind **Advanced setup**.

1. **Looking for StrmQt…** With nothing set up, opening the app goes
   straight to this screen, not to a menu. It searches the network (see
   Discovery, in detail) up to three times, about 7 s in all.
2. What it found decides the next screen:
   - **One StrmQt** that can stream: the code screen, at once.
   - **Several**: **Which StrmQt?**, one large card per host, by name. An
     address is shown only under two hosts that go by the same name. The
     host set up before, if any, is listed first.
   - **None**: **Can't find StrmQt**, with where to start StrmQt's streaming
     host (Settings › Web Remote › Streaming host) and **Try again**. While
     the screen is in front the network is searched again, quietly, every
     10 s, and a StrmQt that turns up is taken straight to the code. A StrmQt
     that answers but runs no Sunshine says **StrmQt is not streaming**
     instead.
3. **The code screen.** "Pairing with *host*", one large four-digit code,
   one line saying where to type it (in StrmQt, Settings › Web Remote ›
   **Pair a StrmLight**, or on StrmQt's phone remote, **Pair a StrmLight**),
   and two ticks, **Picture** (Moonlight with StrmQt's Sunshine) and
   **Remote** (StrmQt's Web Remote). Each turns amber with a check when its
   half is paired. Under them, one line when something needs attention: a
   different code was typed, too many wrong codes (how long to wait), StrmQt
   is not answering, or Sunshine refused. **New code** makes a fresh one.
   Back returns to the chooser when there was one. Nothing is typed on the
   TV.
4. **Connected.** When both halves are paired the screen says so, applies
   the stream preset the first time, and starts the stream 2.5 s later.
   From then on, opening the app goes straight to the stream, and holding
   Back in the stream opens the fork's menu, as before.

When pairing stops for a reason a code cannot fix (StrmQt's certificate
changed, the certificate does not match the one discovery announced, a
StrmQt too old to pair by code), the screen says **Pairing stopped** with
the reason, **Try again** and **Advanced setup**.

**Advanced setup**, a quiet button on every one of these screens, opens what
the simple screens leave out: the full host list with addresses, entering a
host by hand (its address, the Web Remote port 8338 and the Sunshine port,
48989 or the "port" line of StrmQt's `sunshine.conf`), **Pair** and **Stop
pairing**, the two halves' states in full, the Web Remote certificate's
SHA-256 to compare with the one StrmQt shows, Moonlight's own host list,
and Settings. Back, or **Simple setup**, returns to the simple screens.

Moonlight's own host list is not needed: the StrmQt host's Sunshine is
added to it by setup, as its own entry.

Everything is also under Settings, in the **StrmQt** category, including
**Set up StrmQt** and **Pair again**, which forgets the remote's token and
opens the code screen for the stored host with a new code.

### Previewing the setup

A debug build can show each setup screen without touching what is stored:

```
adb shell am start -n com.strmlight.debug/com.limelight.strmqt.PreviewSetup \
    -f 0x10008000 --es StrmQtPreview <scenario>
```

`<scenario>` is `searching`, `one`, `several`, `none`, `nostream`,
`trouble`, `problem`, `connected`, or `live` (the real search, with pairing
faked). The preview fakes discovery and pairing, stores nothing, starts no
stream and is marked "Preview" in its corner. The entry is an
activity-alias that exists only in debug builds (`strmqt_preview_entry`
in `src/debug/res`) and is checked against `BuildConfig.DEBUG` as well.
`-f 0x10008000` starts it in a task of its own, so an earlier preview is
not reused.

### Discovery, in detail

Moonlight's mDNS discovery cannot find StrmQt's Sunshine: the host may run
with mDNS (avahi) off, and the StrmQt host's Sunshine shares the machine
with the desktop's own Sunshine on the default port. StrmQt answers a
discovery protocol of its own instead.

- StrmLight sends the ASCII datagram `STRMQT-DISCOVER 1` to UDP port 8338,
  to 255.255.255.255 and to the broadcast address of each IPv4 interface
  that is up; three times, 500 ms apart, collecting replies for 2 s.
  **Search again** repeats it. A Wi-Fi multicast lock is held meanwhile
  (some Wi-Fi drivers drop broadcasts without one); the manifest now
  declares `CHANGE_WIFI_MULTICAST_STATE` on every API level for that.
- A host answers by unicast with a JSON object:
  `{"service":"strmqt","version":1,"name":"…","remotePort":8338,"sunshinePort":48989,"fingerprint":"AA:BB:…"}`.
  The host's address is the reply's source address; replies are
  deduplicated by it. Unknown fields are ignored. A missing or unusable
  `remotePort` means 8338; a missing `sunshinePort` means the host cannot
  stream, and it is listed but cannot be chosen. A reply without
  `"service":"strmqt"` is ignored.
- A reply is only a hint. The Web Remote's certificate is still pinned on
  first use; when the reply announced a fingerprint and the Web Remote
  presents a different certificate, pairing stops ("does not present the
  certificate StrmQt announced"). The code is what authenticates.
- Builds before this one stored the Web Remote port 8337 (now the desktop
  StrmQt's phone remote). When the stored host is found with its Web
  Remote on another port, that port is stored instead. With **Pair again**,
  or when the app opens pairing by itself, the screen waits for the first
  search before it starts, so the corrected port is the one paired with.

### Pairing, in detail

There is no standing PIN. StrmLight makes up a random four-digit code
(`SecureRandom`) and uses it for both halves at once, while the pairing
screen is in front.

**The stream.** Moonlight's own pairing (`PairingManager`, as Moonlight's
host list does it), with the code as Moonlight's PIN instead of a random
one, against the Sunshine at `<host>:<Sunshine port>`. The computer is first
added to Moonlight's list under that address and port, as Add PC manually
does with `address:port`; Moonlight keys computers by the uniqueid Sunshine
reports, so StrmQt's Sunshine is its own entry and is not merged with
another Sunshine on the same machine. Moonlight's pairing request waits on
the host until the PIN is entered there. StrmQt, once the code is typed
into it, enters it into its Sunshine by itself (retrying for up to two
minutes until the request is waiting).

| Sunshine answers | The screen says | StrmLight |
|---|---|---|
| Already paired with this device | "Stream: paired" | Done; no code needed for it |
| Holds the request | "Stream: waiting for the code" | Waits (one request, no polling) |
| Paired | "Stream: paired" | Pins Sunshine's certificate in Moonlight's database |
| `409 A pairing session with this uniqueid already exists` | "an earlier pairing attempt is still open … trying again every 5 s" | Tries again every 5 s while the code is shown |
| A different PIN was entered | "was given a different code. Type the code … again" | Starts again with the same code after 1 s |
| Refused for another reason | "refused to pair. Trying again in 5 s" | Tries again in 5 s |
| No answer | "does not answer at host:port. Still trying" | Tries again in 5 s |

**The remote.** StrmQt's Web Remote, asked with the code every 2 s
(`POST /api/auth/pin`, `{"pin": "<code>"}`, with the header
`X-StrmLight: <device name>`). The device name is the one in the system
settings, or the maker and model, tidied to one line of at most 40
characters; it is what StrmQt lists under paired devices. StrmQt allows a
StrmLight 50 requests in any 95 s; asking every 2 s stays under that.

| StrmQt answers | The screen says | StrmLight |
|---|---|---|
| No answer to the first connection | "Remote: … does not answer at host:port. Still trying" | Tries again every 2 s |
| `GET` says `required: false` | "StrmQt asks for no remote pairing" (at the end) | Remote done; no token |
| `GET` says a held token is still good | "Remote: paired" | Remote done; no code needed for it |
| `202 {"pending": true}` | "Remote: waiting for the code" | Asks again in 2 s |
| `200 {"token": ...}` | "Remote: paired" | Keeps the token, stops asking |
| `403` | "StrmQt was given a different code" | Asks again in 2 s with the same code (StrmQt drops a typed code after five misses; then it is `202` again) |
| `429` | "too many wrong codes … again in 30 s" | Waits 30 s |
| No answer while pairing | "does not answer … Still trying" | Asks again in 2 s |
| A different certificate | "StrmQt's certificate changed while pairing" | Stops pairing |
| Not the certificate discovery announced | "does not present the certificate StrmQt announced" | Stops pairing, without sending a code |
| `GET` without `pairing` (a StrmQt from before this) | "This StrmQt cannot pair with StrmLight by code" | Stops pairing, without sending a code |

When both halves are paired, StrmLight stores the host, both ports, the
token and the certificate, chooses StrmQt's Sunshine as the host to connect
to, turns remote mode on, and starts the stream 2.5 s later.

- Nothing new is sent once the screen is not in front (another app, the
  screensaver, Back): the Web Remote is not asked, and no new Moonlight
  request is made. A Moonlight request already waiting on the host is left
  to finish, since it is one request and cannot be withdrawn; if the code
  is entered meanwhile, the stream half counts as paired. Everything goes
  on with the same code when the screen is back in front.
- The code stays the same across visits to the screen for 10 minutes, and
  until it has been used, so it can be read here and typed in elsewhere.
  **New code** makes a fresh one: the remote asks with it at once, the
  stream from its next request (a Moonlight request already waiting on the
  host keeps the old code; if the new code is entered into StrmQt, that
  request fails as a wrong PIN and the next one carries the new code, which
  may then have to be typed into StrmQt once more). **Stop pairing** stops
  both.
- A token is sent only over the certificate it was paired over. Choosing
  Pair again with a token StrmQt still knows, the same certificate, and a
  Sunshine that still knows this device pairs without a code; a changed
  certificate always pairs by code.
- A `401` in the stream, or a launch that finds Sunshine no longer knows
  this device, opens the pairing screen; the halves that are still paired
  finish by themselves.
- A previously stored PIN is deleted on the first start of this version;
  such a device opens the pairing screen once.

## What it does

### The remote (transport A, StrmQt Web Remote)

While streaming with remote mode on, key events from a remote are taken out
of Moonlight's input path and sent to StrmQt as actions over HTTPS. A device
counts as a remote when it has no gamepad buttons, no joystick axes and is
not an alphabetic keyboard; game controllers and real keyboards are handled
exactly as upstream.

| Button | Press | Hold |
|---|---|---|
| D-pad | `nav.up/down/left/right` | repeats: 400 ms, then every 90 ms |
| Select | `nav.select` | |
| Back | `nav.back` (sent on release) | 2 s: the fork's menu |
| Menu | `app.toggleMenu` (on release) | 0.5 s: `nav.contextMenu` |
| Play/Pause | `player.togglePause` | |
| Rewind | `player.seekBackward` (on release) | 0.5 s: `player.seekBackwardLong` |
| Fast-forward | `player.seekForward` (on release) | 0.5 s: `player.seekForwardLong` |

- One kept-alive connection, requests in order on a thread of their own. A
  request every 20 s keeps it open (StrmQt closes a connection idle for 60 s).
- The certificate is pinned at pairing; any other certificate is refused
  before a request is sent. Its name is not checked, since the certificate
  lists only the addresses the host had when it was made and the pin
  identifies it exactly.
- The token is kept until the device is removed in StrmQt: StrmQt keeps it
  across its restarts, sign-outs and profile switches. While StrmQt is
  unreachable it is retried every 2 s, with the same token.
- A `401` to a press, or a check that finds the token no longer good, means
  the device was removed in StrmQt: the token is deleted, nothing more is
  sent (no loop on `401`), the label says so, and the pairing screen opens
  (leaving the stream ends it; once paired, the launcher reconnects). If the
  app is opened with remote mode on and no token, it opens the pairing
  screen instead of the stream; Back from there lands in the menu.
- A repeat is skipped while a request is still queued; a press older than
  0.7 s when its turn comes is dropped; a failed request drops everything
  queued behind it. Nothing is replayed late.
- When the channel is down a small label in the bottom corner says why. It
  does not take focus or block the stream.

### The remote (transport B, gamepad through Sunshine)

Chosen under Settings, Remote transport. The remote's keys go to Moonlight's
controller path as D-pad, A (Select), B (Back, on release), Start (Menu),
X (Play/Pause) and the shoulder buttons (Rewind, Fast-forward). Hold-Back
still opens the fork's menu.

### The fork's menu

Hold Back for 2 seconds while streaming: a StrmQt panel over the stream
with **Return to StrmQt** (focused), **Disconnect** and **Settings**. The
first time remote mode streams, a label at the bottom of the stream says
"Hold Back for 2 seconds to open the menu" and fades after 5 s.

## Look

StrmLight's own screens (setup, the launcher menu, the menu over the
stream, the remote's labels) wear StrmQt's "Projection Booth" look, from
StrmQt's `docs/BRANDING.md`, so the TV reads as part of StrmQt.

- **Colours** (`values/strmqt_colors.xml`): ground `#0C0B0A`; surfaces
  `#141210`, `#1D1A17`, `#262220`; hairline `#2E2A26`; text `#F5F1EA`,
  `#A29A8E`, `#928A80`; amber `#F0A02A` (muted `#7A5416`, text on amber
  `#0C0B0A`). Amber is for focus, the primary action and what is done,
  never decoration.
- **Type**: Archivo at weight 600 for titles and the wordmark, Public Sans
  for text, IBM Plex Mono for the code and labels. The fonts are bundled
  under `assets/fonts` with their licences (SIL Open Font License 1.1) and
  set by `Brand` from a view's `android:tag` (`display`, `mono`,
  `mono-bold`, `body-medium`; Public Sans when there is none). Sizes are for
  ten feet: titles 34sp, text 20sp, the code 96sp.
- **Shapes**: radii 4, 8, 12 dp and pills.
- **Focus**, as StrmQt draws it: a 3dp amber ring standing clear of the
  button, the button lifting to 1.05 over 160 ms (cards 1.02), and the
  primary button filled amber. The ring is drawn in the button's own
  background (`drawable/strmqt_button*.xml`), so `Brand.unclip` lets it
  show past its parents.
- **Wordmark**: "Strm" in cream, "Light" in amber, beside StrmQt's aperture
  symbol (`drawable-nodpi/strmqt_symbol.png`, rendered from StrmQt's
  `assets/brand/strmqt-symbol.svg`), as StrmQt sets "Strm" and "Qt".
- **Icons**: the launcher icon is StrmQt's Aperture-Q and is unchanged. The
  TV banner was redrawn with the symbol and the wordmark in Archivo, and
  "for StrmQt" in Plex Mono.
- **Upstream screens** (the host list, Settings, dialogs, the stream's own
  overlays) keep their layouts, but the app theme (`values/styles.xml`)
  gives them the ground, surfaces, text colours and amber accent.

### Connect on launch, reconnect

`LaunchActivity` is the app's launcher entry. With a host chosen and Connect
on launch on, it finds the host through Moonlight's `ComputerManagerService`
(sending Wake-on-LAN if it is offline), looks up the app named `StrmQt`
(or the only app there is) and starts Moonlight's `Game`.

A stream that fails to start or ends without the user asking is started
again after 1, 2, 4, 8, 15 and 30 s; then it gives up to the menu. A stream
that stayed up 30 s resets the count. Disconnect from the menu does not
reconnect. Leaving the app ends the stream, as in upstream; coming back
reconnects.

### Stream preset

3840x2160, 30 FPS, HEVC forced, 40 Mbps, "Balanced" frame pacing; or
1920x1080 at 15 Mbps when no decoder on the device reports 4K HEVC. Written
once into Moonlight's ordinary settings, where each value can be changed.
The screen is kept on while streaming by upstream already.

### Start with the device (optional, off by default)

Two settings: **Start with the device** (after boot) and **Return on wake**
(a small foreground service that hears the screen come on). Both open the
app as the home screen would.

Starting an activity from the background needs the "display over other apps"
permission, granted once over adb:

```
adb shell appops set com.strmlight SYSTEM_ALERT_WINDOW allow
```

(`com.strmlight.debug` for a debug build.) Device settings that keep the
Fire TV from sleeping or showing its screensaver are changed over adb too,
not by the app. These are the usual ones, written from memory and not
tried on a current Fire OS:

```
adb shell settings put secure sleep_timeout 0
adb shell settings put system screen_off_timeout 2147483647
adb shell settings put secure screensaver_enabled 0
```

An Amazon update can break any of this. Opening the app by hand always works.

## What was found reading the code (phase 0, in part)

Read from upstream at `b48494cb`, not observed on a device:

- Stock Moonlight already treats a remote as a controller: any device that
  is not an alphabetic keyboard goes to `ControllerHandler`, where the D-pad
  is the D-pad, Select is A and Menu is Start. So with a stock client, those
  already arrive at Sunshine as gamepad buttons.
- Back from a device named "…remote", or any external device without gamepad
  buttons, is deliberately not captured: it leaves the stream.
- Play/Pause, Rewind and Fast-forward are not controller buttons and have no
  mapping in `KeyboardTranslator`; they are not sent.
- `ShortcutTrampoline` exists and starts a stream from an intent, but it
  needs the app list to be cached from an earlier visit, reports failure only
  as a dialog, and cannot retry. `LaunchActivity` uses the same service and
  the same start intent instead.
- Moonlight accepts `address:port` when adding a host, and upstream keeps
  the screen on while streaming.

Read from StrmQt at `1d5cebf`:

- A wrong PIN is answered `403`; a POST without `Content-Type:
  application/json` is `415`; `GET /api/auth/pin` says whether a PIN is
  required and whether the token is still good. (Since replaced by pairing
  by a code StrmLight shows; see Pairing, in detail. That part follows
  StrmQt's `WebRemoteServer.cpp` as of the `streaming-host` branch at
  `a453357`.)
- **The Web Remote runs whether or not StrmQt is signed in to Emby** (StrmQt
  `b5b501d`): pairing is the device's, not the Emby account's, so the remote
  also drives StrmQt's sign-in screen and profile picker. Earlier StrmQt builds
  stopped it while signed out.

## Tested, and not

| | How |
|---|---|
| Gestures: repeat timing, long presses, hold-Back, no run-on | Unit tests |
| Web Remote client: pinning; pairing by code (waiting, typed in, wrong code, 429 wait, pairing off, a held token still good or removed, older StrmQt, the device-name header); staying paired across a restart; a removed device not looping on `401`; recovery; dropping stale presses | Unit tests, against a stand-in server written from StrmQt's source |
| Discovery: reading replies (complete, unknown fields, missing or bad ports, bad fingerprints, garbage), deduplication, the probe and its timing over loopback | Unit tests, against a stand-in host on loopback (never port 8338) |
| One-code pairing: both halves from one entry; a stale Sunshine session (`409`) retried until it clears; a wrong PIN; Sunshine unreachable or refusing; halves already paired; a token held for another certificate; pausing (including a Moonlight request finishing while paused); the announced certificate checked; a new code; lockout; an older StrmQt; stopping | Unit tests, with a stand-in Sunshine behind the stream-pairing interface and the stand-in Web Remote |
| First-run decisions: what opening the app does, which screen follows a search (one, several, none, hosts that cannot stream, a host found late, Pair again with the stored host, after the user stopped pairing), the chooser's order and when it shows addresses, the code screen's ticks and its one line of trouble | Unit tests (`FirstRunTest`) |
| Every simple setup screen, Advanced setup and back, live discovery finding StrmQt, the launch straight into the stream, the menu over a running stream, upstream's host list and Settings in the palette | On a Fire TV (API 28), through the debug preview, which stores nothing; the TV kept its pairing |
| Launcher menu, settings category, preset written | Android emulator (phone image, API 36, landscape), against a stand-in server, with the earlier PIN pairing |
| The setup screen with discovery and one-code pairing, `MoonlightPairer` against a real Sunshine, Pair again, the removed-device path in the stream | Builds and installs on a Fire TV (API 28); not yet run against StrmQt |
| Unchanged upstream build | Built before any change |

Not yet seen on a device with the new screens: a real first run (it needs a
device with nothing stored), pairing by code through them, Pair again, the
remote's labels and hint in the stream, and the launcher menu.

Not tested at all, because no Fire TV, no paired Sunshine and no signed-in
StrmQt were available when this was built:

- Anything inside a running stream: remote mode in `Game`, the hold-Back
  menu, the indicator, transport B, reconnecting after a dropped stream.
- The real StrmQt Web Remote (the client has only met the stand-in), StrmQt's
  discovery reply, and Moonlight pairing driven by StrmQt entering the code.
- A real Fire TV remote's key codes and device flags.
- 4K HEVC decode and button-to-screen latency.
- Start with the device and Return on wake, on any device.
- Acceptance items 1 to 8 of the plan on a Fire TV.

### First things to check on a Fire TV

```
adb logcat | grep -i -E "strmqt|controller context"
adb shell getevent -lt          # the remote's raw keys
adb shell dumpsys input | grep -A12 -i remote
```

and on the host, `sudo evtest` for what Sunshine's virtual devices receive
with transport B.
