# TVs in step: a design for synchronized playback

Status: 2026-10-18. The clock (section 1) is built on the `tv-sync` branches of StrmQt and
StrmLight; nothing else here is yet.

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

**Where it stands (measured by ear, 2026-10-18).** With stock Moonlight in a real home, TVs
playing the same host differ by well under 200 ms, probably within ~50 ms. Nobody notices
that in the picture or in input lag, but the sound is a clear echo between rooms. So
**audio is the problem worth solving first**, and the gap to close is tens of ms, not
hundreds.

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

**Closing the triangle.** Each StrmLight answers the same requests while it streams
(`TimeResponder`, UDP 8339), so two TVs can time each other as well as the host.
- **What it checks.** Around host → A → B → host the three offsets must add up to zero.
  What is left is the asymmetry error the host's figures carry, which no single round
  trip can see. The sync is then measured, not assumed.
- **More TVs.** With three or more TVs, a least-squares fit over every link spreads out
  the random error. The direct A↔B link is exactly the quantity the echo depends on.
- **The limit.** Triangulation cannot remove an asymmetry that is consistent around the
  loop, and it adds nothing with a single TV.

**Built so far.**
- StrmQt's `TimeService` and its `timePort` in `GET /api/strmlight/host`.
- StrmLight's `TimePacket`, `ClockEstimate` (the lowest-delay exchanges of each eighth of
  a 200-exchange window, with a line through them for skew), `HostClock` (a 20-exchange
  burst 50 ms apart, then every 250 ms) and `TimeResponder`.
- The clock's line in the performance overlay.

Checked between Java and C++ on one machine, where the true offset is zero: within 10 µs,
with a 0.05 ms round trip.

**Measured on Wi-Fi, 2026-10-18.** Two Fire TV sticks (AFTKA002, AFTSS; Fire OS 7) ran the
clock code for 5 minutes against `TimeService` on the host machine, and against each
other:

| | Round trip | Scatter about the fit | Skew to host |
|---|---|---|---|
| Stick A ↔ host | 1.2 ms | 0.03 ms | +8.7 ppm |
| Stick B ↔ host | 1.3 ms | 0.04 ms | +0.7 ppm |
| A ↔ B | 2.5 ms | 0.08–0.18 ms | — |

- **The triangle.** A→B against A→host→B closes to within +0.17 ms (median; −0.04 to
  +0.36 ms over the run).
- **The mirror check.** A's view of B and B's view of A disagree by the same +0.17 ms. So
  the residual is the TVs' own answering, not the host links. A TV reads t2 when its thread
  wakes rather than from the kernel, and t3 before Java's send.
- **Conclusion.** Each TV knows the host's clock to a few tenths of a millisecond, and two
  TVs agree with each other to well under a millisecond. The clock will not be what limits
  the echo. The audio path is. Next: TVs report their figures to the host, and the host gives
each TV its peers to time.

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

### 4. The group delay *D*: line up on the slowest TV

Sync costs latency, and a remote-driven UI wants none. So it is a mode, **on only while
two or more TVs stream the host**. A single TV keeps today's path untouched.

When it is on, nobody waits for a fixed buffer: **every TV lines up on the slowest one**.
- **What each TV reports.** Its natural latency, from host capture to sound out, measured
  with the clock and `getTimestamp`. It reports over the existing HTTPS channel with its
  bearer token, e.g. a new `POST /api/strmlight/sync`.
- **How *D* is set.** StrmQt sets *D* to the largest report plus a margin for that TV's
  jitter, and publishes it in the time reply.
- **The cost.** The slowest TV keeps the latency it has today. The others delay their
  audio by the difference, the ~50 ms heard as echo now.
- **Lip sync.** Each TV delays its video by the same amount as its audio, so lip sync on
  each TV is unchanged. The video timeline (section 2) does this exactly; a plain hold of
  the same number of ms would also do.
- **Changes.** When *D* changes, every TV holds or skips once to the new target, and that
  is heard. So while the host plays, *D* only goes up (when a TV comes within 5 ms of it),
  and comes down between things played. A TV alone is held by its own latency, so a faster
  one joins without moving it, and the host remembers a TV's latency for half an hour after
  it stops, so when it comes back the others are already held for it.
- **Joining.** A TV that joins is muted and shows "Syncing with other devices…" until it is
  in step with a *D* that counts its own latency; the others hear nothing. Only a TV slower
  than every TV the host remembers moves *D*, once. A TV is held only while it joins: a
  later change of *D* is a jump, not another wait. Its latency is measured the same way
  before and after it is scheduled (what the track has played but not yet heard, plus what
  scheduling keeps written ahead of it), so its first reports do not overshoot.
- **Fire TV.** Audio paths of 300–500 ms have been reported on some models and firmware
  (moonlight-android issues #1238, #1423). A TV like that becomes the slowest, and the
  others wait for it.

### Measuring without a camera

The clock gives every TV a software measurement of the echo. For each audio packet, a TV
knows the host time it was captured (its timeline) and the host time it came out of the
DAC (`getTimestamp` mapped through the clock offset). Each TV reports that number, and
StrmQt shows the spread between TVs in `strmqt admin status` and in Settings. It misses
only what happens after the HDMI port, which the per-TV offset covers.

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
| Today | within ~50 ms by ear: not noticed | within ~50 ms by ear: a clear echo |
| Phases 1–4 | about one refresh (≤ 17 ms at 60 Hz), plus each TV's panel latency until calibrated | a few ms, plus what happens after HDMI until calibrated |
| Phase 5 audio | — | ~1 ms plus calibration |

These are estimates; the per-model behaviour can only be measured on the TVs.
Specifically:
- how stable `getTimestamp` is on Fire OS;
- how many decoded buffers a Fire TV codec lets us hold;
- how well the clock sync does over Wi-Fi.

**Audio, measured 2026-10-18.** Phases 1–3 for audio, on a Fire TV set (AFTKA002) and a
Fire TV Stick (AFTSS) streaming one StrmQt host over Wi-Fi. Walking between the two, the
sound could not be told apart: no echo. By the TVs' own figures each plays within ±1.5 ms
of the host's schedule, with a few one-frame corrections a second and no jumps once
settled.
- **Delay.** It settles at ~415 ms. The stick's sound is heard ~350 ms after it is
  written, and the other TV waits for it; the Fire TV set's own path is ~190 ms.
- **What it took.**
  - `getTimestamp` on the stick moves a chunk at a time (raw error ±17 ms), so frame 0's
    time is smoothed.
  - A track takes far more than it is asked for, so at most 60 ms is written ahead.
  - Every change of *D* is heard, so the host moves it only when it must.
- **Still open.**
  - Video (phase 4) followed the same day, below.
  - The first 20–40 s overshoot *D* before it settles.
  - Each TV's latency after HDMI is not measured; here it did not matter.

**Video, the same day.** Each frame is due *D* after the host time of its RTP timestamp,
found from arrivals as for audio, so the picture keeps the lip sync it had on the host.
Lip sync held by ear on both TVs, and every frame reached the display on time.
- **Where frames wait.** Before the decoder: it is fed 100 ms before a frame is due, and
  the picture goes to the display with its due time (`releaseOutputBuffer(index, time)`).
  A Fire TV's decoder lends out only a few pictures, so holding decoded ones would stall it.
- **No change to the fork.** moonlight-common-c already gives each frame's `rtpTimestamp`.

## Plan

Audio first, since it is what is heard. Today's ~50 ms is invisible in the picture.

1. **Clock and measurement.**
   - The host time service in StrmQt and the clock client in StrmLight.
   - Each TV reports its capture-to-DAC audio latency, and StrmQt shows the spread between
     TVs. This is the measurement, in place of a camera.
2. **Audio timeline.** The moonlight-common-c fork with timestamped audio, scheduled
   `AudioTrack` playback, `getTimestamp` feedback and drift correction.
3. **The group.** Sync on automatically with two or more TVs, *D* set by the slowest TV,
   and per-TV offsets in settings.
4. **Video follows.** `rtpTimestamp` through JNI, the `E` estimate, and each TV's video
   held by the same delay as its audio, so lip sync is kept. ALLM goes in here too.
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
