# TVs in step: a design for synchronized playback

Status: investigation, 2026-10-18. Nothing here is built yet.

Several StrmLights can stream one StrmQt host at once — the same film in two rooms, music
through the house. Today they are close, not in step: each TV shows the live picture after
its own network, decoder and audio delay, and nothing lines them up (StrmQt's
ARCHITECTURE.md, "nothing keeps two TVs in step"). This is how they could be lined up,
with timing StrmQt and StrmLight own, and without changing Sunshine.

## What "in step" means here

Every TV already shows the *same content*. A host has one KWin screen, one StrmQt and one
mpv; Sunshine captures that screen once and gives each TV its own encode of the same
captured frames (Sunshine PR #3325: up to 128 sessions, shared capture, an encoder and an
audio capture per session). Pause and seek are shared by definition.

So the problem is only **delay**: make every TV present the frame and the sound captured
at host time *T* at the same moment, *T + D*, where *D* is one delay for the whole group.
Nothing has to agree on *what* to play, only on *when*.

Targets: video within a frame or two (it can never be better than about one refresh,
because each TV's vsync runs on its own crystal); audio within ~5 ms between rooms for
music (an echo is heard from ~10–20 ms).

## What already exists

**Video carries a shared timeline already.** Sunshine stamps every video packet with the
frame's capture time in 90 kHz ticks, counted from `video_epoch`, which is set once per
broadcast thread and so is the same for every session (Sunshine `src/stream.cpp`:
`video_epoch = steady_clock::now()` in `videoBroadcastThread`; `timestamp =
round<90 kHz>(frame_timestamp − video_epoch)`). The same captured frame therefore has the
same RTP timestamp on every TV. Duplicate frames get the rate-control time instead. The
frame header also carries the host's processing latency in 0.1 ms.

moonlight-common-c keeps it (`DECODE_UNIT.rtpTimestamp`, `presentationTimeUs`;
`RtpVideoQueue.c:158-159`), but **StrmLight drops it at the JNI bridge**: `callbacks.c`
passes only `frameNumber, frameType, frameHostProcessingLatency, receiveTimeUs,
enqueueTimeUs` up to `MediaCodecDecoderRenderer.submitDecodeUnit` (MCDR:1346).

**Audio has a timeline per session only.** Its RTP timestamp is a counter that starts at 0
for each session and steps by the packet duration in milliseconds (5 ms by default;
Sunshine `stream.cpp` `session->audio.timestamp += packetDuration`). It has no link to
capture time or to the video clock, and moonlight-common-c does not pass it to the
renderer at all: `decodeAndPlaySample(char*, int)` gets the Opus bytes only
(`Limelight.h:357`, `AudioStream.c:218/235`). Each session records the host's sink
separately, but the host's audio clock is PipeWire's dummy driver on the host's
monotonic clock, so all sessions' audio advances with host time.

**Both are played as soon as they arrive.**
- Video: StrmQt forces Moonlight's "balanced" pacing (`StreamPreset.java:71`), which
  releases at most one frame per vsync from a 2-frame queue (MCDR:948-992, 117-118).
- Audio: a low-latency `AudioTrack`, written with a blocking write and dropped when more
  than 40 ms is pending (`AndroidAudioRenderer.java:189-200`). It never calls
  `getTimestamp`, and the first 500 ms of audio are discarded in C (`AudioStream.c:248`).

**No clock is shared with the host.** ENet's smoothed RTT (`getEstimatedRttInfo`) is the
only network timing, and it carries no host time. StrmLight's channel to StrmQt is HTTPS
request/response on port 8338 for button presses, and only in remote mode; no response
carries a server timestamp. StrmQt's `/api/events` SSE stream exists but StrmLight does
not use it.

## The design

### 1. One clock: the host's

StrmQt answers time requests: NTP's four timestamps over UDP, Snapcast-style.
- **Port.** A new port in the host's block, e.g. 8339 (free; UDP 8338 is discovery).
- **Host side.** Answered on a thread of its own, not the GUI thread the remote server
  runs on. t2 comes from the kernel's receive timestamp (`SO_TIMESTAMPNS`), t3 is read
  just before the send, both on `CLOCK_MONOTONIC` — the clock Sunshine's `steady_clock`
  stamps capture with.
- **Abuse.** Same rules as discovery: private and link-local senders only, and the reply
  is no bigger than the request.

StrmLight keeps `offset = host − local(System.nanoTime)` and a skew, on a dedicated
thread holding the Wi-Fi low-latency lock it already takes:
- a burst of ~50 requests on joining, then 2–5 per second;
- the estimate comes from the lowest-RTT ~15% of a 200-sample window;
- skew is fitted over minutes.

Expected accuracy is sub-millisecond on a quiet LAN. Wi-Fi power-save and uplink/downlink
asymmetry bias it by an unknown amount; it has to be measured (below). PTP buys nothing
without hardware timestamps, which Android does not give.

The reply also carries the group's state: `groupDelayMs` and a generation number. TVs
then learn a change within half a second without an SSE stream.

### 2. Video: map the 90 kHz timeline to host time

Each TV estimates `E`, the host time of `video_epoch`, from frames it receives:

    E ≈ min over recent frames ( hostTime(firstPacketReceive) − rtp/90 kHz
                                 − hostProcessingLatency − oneWayDelay )

using its clock offset, the frame header's processing latency and half the time-sync RTT.
- **Error.** Each TV's error is the error in its one-way delay, a few ms at worst; that is
  well under one refresh.
- **Restart.** `E` changes only when Sunshine's broadcast thread restarts (every session
  ended), which a TV sees as a jump in the timeline.

Then frame *f* is due at host time `E + rtp_f/90 kHz + D + offsetTV`.

StrmLight changes:
- **The bridge.** `rtpTimestamp` goes up through JNI next to the existing fields
  (`callbacks.c:168-192`, `MoonBridge.java:220`, MCDR:1346). No change to
  moonlight-common-c.
- **A new pacing mode, "synced".** It sits next to latency, balanced, smoothness and
  cap-fps (`PreferenceConfiguration.java:112-115`).
- **Holding frames.** Compressed frames are held until shortly before they are due, then
  decoded. Each output buffer is released with `releaseOutputBuffer(idx, targetNs)` about
  two vsyncs ahead. MediaCodec only honours targets within 1 s of now, and processes
  buffers in order, so the hold has to be ours, not the codec's.
- **Vsync alignment.** The target is snapped to the nearest vsync using
  `getAppVsyncOffsetNanos`, and on API 33+ `FrameTimeline.getExpectedPresentationTimeNanos`,
  as Media3's `VideoFrameReleaseHelper` does. A frame that is late is dropped, not shown
  late.
- **Feedback.** `onFrameRenderedListener` is already wired but switched off
  (`USE_FRAME_RENDER_TIME`, MCDR:42). Switched on, it reports the real render time for
  telemetry and correction.
- **Not tunneled playback.** It hands frame timing to vendor code.

### 3. Audio: schedule the samples, follow the host clock

Each TV estimates its session's audio base `A` the same way as `E`, from packet receive
times. Packet *n* is due at `A + ts_n + D + offsetTV`.

StrmLight changes:
- **The audio timestamp.** It has to come up from C, which needs a small change to
  moonlight-common-c: a timestamped variant of `decodeAndPlaySample`, fed from
  `decodeInputData`. The submodule is pristine upstream today (874ac95), so this means our
  own fork of it — a new dependency, whose reason is exactly this.
- **Removals.** The 40 ms drop guard and the play-on-arrival write go. A small jitter
  buffer keyed by timestamp takes their place.
- **Starting.** Silence is written, or samples trimmed, so the first sample lands on its
  target.
- **Measuring.** `AudioTrack.getTimestamp` (`TIMEBASE_MONOTONIC`, the `System.nanoTime`
  clock) says when written frames actually play. Poll it fast at start and after a route
  change, about once a second after that, and low-pass it; Google warns that short-term
  deltas are noisy. When it is unavailable, fall back to `getPlaybackHeadPosition`, as
  Media3's `AudioTimestampPoller` does.
- **Drift.** The TV's DAC drifts from the host by tens of ppm, a few ms a minute, so drift
  is corrected continuously. Snapcast drops or doubles single samples, ±0.05% at most,
  from ~0.1 ms of error; above ~10 ms it resyncs hard.
- **Lip sync.** Video and audio both follow the host clock, so their relationship is
  whatever it was at capture on the host. No audio-master clock between rooms.

### 4. The group delay *D*, and when sync is on

Sync costs latency, and a remote-driven UI wants none. So it is a mode, **on only while
two or more TVs stream the host**. A single TV keeps today's low-latency path.

When sync is on, *D* is adaptive:
- Each TV reports the earliest it could present: its network delay, decode time and audio
  pipeline latency, measured as above. It reports over the existing HTTPS channel with its
  bearer token, e.g. a new `POST /api/strmlight/sync`.
- StrmQt sets *D* to the largest report plus a margin, and publishes it in the time reply.
- Expect 150–300 ms. Fire TV audio paths of 300–500 ms have been reported on some models
  and firmware (moonlight-android issues #1238, #1423); those raise *D*.
- When *D* changes, or a TV joins or leaves, the others hold or skip once to the new
  target.

### 5. The last metre: each TV's own latency

Android never reports the delay after the HDMI port: the TV's processing, a soundbar, an
AVR. Every shipping product handles it with a per-device offset:
- Google Cast's "group delay correction" (0–80 ms by device type);
- Snapcast's per-client latency;
- Squeezebox's `playDelay`.

So:
- **A per-TV offset in ms.** It is kept on the host by device and set from the TV's
  settings or StrmQt's.
- **Lower and steadier TV latency.** `Window.setPreferMinimalPostProcessing(true)` asks
  the TV for its game mode (ALLM) over HDMI.
- **PCM audio out.** Avoid Dolby re-encode and passthrough, whose latency may not show in
  `getTimestamp` (unverified).
- **Later: calibrating with a phone,** Apple TV's "wireless audio sync" method.
  - StrmQt plays a click on one TV at a known host time.
  - The Web Remote records it on a phone whose clock is synced the same way, and the
    offset follows from the arrival time.
  - This needs `getUserMedia` in the Web Remote's HTTPS page; whether a self-signed origin
    counts as a secure context there is unverified.

### 6. Later, for music: StrmQt's own audio stream

Moonlight audio is one capture and one encode per session, with a per-session timeline
estimated from receive times. For music through the house, StrmQt can do better:
- **What it would do.** Capture the host sink's monitor once, stamp each chunk with host
  capture time, Opus-encode it once, and send the same packets to every TV in the group.
  StrmLight plays that and ignores Moonlight's audio.
- **What it buys.**
  - Every room gets identical samples on an exact timeline: no estimate, and no 500 ms
    startup drop.
  - Audio-only listeners become possible.
- **The cost.** Another stream with its own port and loss handling. It is not needed for
  the first version.

## Expected result

| | Video between TVs | Audio between TVs |
|---|---|---|
| Today | network + decoder + audio-path differences: tens to hundreds of ms (unmeasured) | the same |
| Phases 1–4 | about one refresh (≤ 17 ms at 60 Hz) plus each TV's panel latency, after calibration | a few ms plus each TV's audio latency, after calibration |
| Phase 5 audio | — | ~1 ms plus calibration |

These are estimates; the per-model behaviour can only be measured on the TVs.
Specifically:
- how stable `getTimestamp` is on Fire OS;
- how many decoded buffers a Fire TV codec lets us hold;
- how well the clock sync does over Wi-Fi.

## Plan

0. **Measure first.**
   - **Test pattern.** StrmQt gets one: a white flash and a click on every whole host
     second.
   - **Baseline.** Film two TVs with a phone's slow-motion camera and record their audio,
     to measure today's skew. Two TVs together have never been tried (StrmQt 0.9.1 notes).
   - **Calibration.** The same pattern is what calibration uses later.
1. **Clock.** The host time service in StrmQt and the clock client in StrmLight, with
   offset, RTT and skew in the performance overlay.
2. **Video timeline.** `rtpTimestamp` through JNI, the `E` estimate, and the "synced"
   pacing mode with held frames and scheduled release.
3. **Audio timeline.** The moonlight-common-c fork with timestamped audio, scheduled
   `AudioTrack` playback, `getTimestamp` feedback and drift correction.
4. **The group.** Sync on automatically with two or more TVs, adaptive *D*, per-TV
   offsets in settings, and ALLM.
5. **Music grade.** StrmQt's own audio stream, and phone calibration.

Nothing in 1–5 changes Sunshine. Phase 3 is the only one that changes Moonlight's C core.

**Testing needs two Android TV clients.**
- The Fire TV at 192.168.2.158 is in production. It can be used only by asking first, and
  never reset.
- A second device is needed for real numbers: another Fire TV or Google TV stick, or an
  Android phone running StrmLight.
- An Android TV emulator is enough to develop phases 1–2 against. Its software decoder
  says nothing about real timing.

## Sources

- **Sunshine** `src/stream.cpp` (video epoch and timestamps, audio counter),
  `src/video.cpp` and `src/audio.cpp` (per-session encoders), PR #3325 (multiple
  sessions).
- **moonlight-common-c** 874ac95: `Limelight.h`, `RtpVideoQueue.c`, `VideoDepacketizer.c`,
  `AudioStream.c`, `ControlStream.c`.
- **StrmLight** `MediaCodecDecoderRenderer.java`, `AndroidAudioRenderer.java`,
  `jni/moonlight-core/callbacks.c`, `strmqt/StrmQtClient.java`.
- **StrmQt** `ARCHITECTURE.md` (streaming host), `src/remote/WebRemoteServer.cpp`,
  `src/platform/streaming/HostFiles.cpp`.
- **Android**
  - `MediaCodec.releaseOutputBuffer(int, long)`: scheduled within 1 s, about two vsyncs
    ahead.
  - `AudioTrack.getTimestamp` and `AudioTimestamp`.
  - `Display.getAppVsyncOffsetNanos`, `Choreographer.FrameTimeline`.
  - `Window.setPreferMinimalPostProcessing`.
  - Amazon's Fire TV A/V sync guide.
- **Media3** `AudioTimestampPoller`, `AudioTrackPositionTracker`,
  `VideoFrameReleaseHelper`.
- **Prior art.**
  - Snapcast: time sync by median, ±0.05% sample correction, Oboe timestamps on Android.
  - AirPlay 2 / shairport-sync (PTP; ~0.5 s latency).
  - Sonos (SNTP; US8234395).
  - Google Cast group delay correction.
  - Lyrion/Squeezebox `playDelay`.
