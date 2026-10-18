# StrmLight

**Watch [StrmQt](https://github.com/mpengellyCA/strmQt-linux) on a Fire TV or Android TV, and drive it with the TV's own remote.**

StrmQt is a native Emby client for Linux. Its *streaming host* runs it on a computer with no screen of its own, for a TV to watch. StrmLight is the TV side: it finds that computer on your network by itself, pairs with one four-digit code, and streams StrmQt full screen. The TV's remote drives StrmQt directly.

StrmLight is a fork of [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android) 12.2, made for StrmQt. It is not affiliated with the Moonlight project.

## Install

Download `StrmLight-<version>.apk` from [Releases](https://github.com/mpengellyCA/strmLight-android/releases). It is not in the Amazon or Google stores yet.

- **Fire TV:** install the *Downloader* app from the Amazon store, allow it to install unknown apps (Settings › My Fire TV › Developer options › Install unknown apps), and open the APK's link in it.
- **From a computer:** turn on ADB debugging on the TV, then `adb connect <tv-address>` and `adb install StrmLight-<version>.apk`.

Android 5.0 or newer. It installs beside Moonlight and leaves Moonlight's settings and pairings alone. StrmLight streams StrmQt only: it has no list of other computers or games. A newer release installs over an older one and keeps its pairing.

## Set up

You need StrmQt 0.9.0 or newer on the computer, with its streaming host set up once: StrmQt › Settings › Web Remote › **Streaming host**.

1. Open StrmLight. It looks for StrmQt on your network and shows a four-digit code. If it finds more than one StrmQt it asks which, by name.
2. Type the code into StrmQt, under Settings › Web Remote › **Pair a StrmLight**, or on StrmQt's phone remote under **Pair a StrmLight**. Nothing is typed on the TV.
3. That one code pairs both the picture and the remote. StrmLight says *Connected* and starts the stream.

From then on, opening StrmLight goes straight to StrmQt. Hold **Back** for two seconds while streaming for StrmLight's menu: Return to StrmQt, Disconnect, Settings.

**Advanced setup**, on every setup screen, has what the simple path hides: entering the host by hand (address, Web Remote port 8338, Sunshine port), the certificate fingerprint, and the stream settings.

## What it does

- **Finds StrmQt by itself** with a UDP broadcast to port 8338, answered only on the local network.
- **One code pairs everything.** The code StrmLight shows is also its Moonlight PIN: StrmQt arms its remote with it and enters it into its own Sunshine.
- **Streams only StrmQt's own Sunshine.** StrmQt vouches for its Sunshine's certificate over the pinned Web Remote, and StrmLight streams only from the Sunshine its StrmQt vouches for: a plain Sunshine, or the desktop's own on the same machine, is refused. This needs a StrmQt newer than 0.9.0; an older one is refused with a message to update it.
- **The remote goes straight to StrmQt** through StrmQt's Web Remote, pinned to its certificate, rather than as key presses through the stream.
- **Connects on launch and reconnects** by itself after a dropped stream.
- **Looks like StrmQt**: the same warm black, amber accent and type, with focus you can see from the couch.

The details, including the pairing protocol and what has and has not been tested, are in [docs/strmqt-fork.md](docs/strmqt-fork.md).

## Building

```
git submodule update --init --recursive
./gradlew :app:testNonRootDebugUnitTest      # unit tests
./gradlew :app:assembleNonRootDebug          # debug APK (com.strmlight.debug)
adb install -r app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
```

Needs JDK 17 or newer and the Android SDK; Gradle fetches the NDK and SDK platform `app/build.gradle` names.

A debug build can show each setup screen without touching what it has stored: see *Previewing the setup* in [docs/strmqt-fork.md](docs/strmqt-fork.md).

### Releases

Releases are built by GitHub Actions. Pushing a tag `v<version>` that matches `versionName` in `app/build.gradle`, with notes in `docs/releases/<version>.md`, tests and builds the APK, signs it with the release key held in the repository's secrets, and publishes it (a pre-release before 1.0). Locally, a release build is signed only when `STRMLIGHT_KEYSTORE`, `STRMLIGHT_KEYSTORE_PASSWORD`, `STRMLIGHT_KEY_ALIAS` and `STRMLIGHT_KEY_PASSWORD` are set; otherwise it is left unsigned.

## License and credits

GPL-3.0, as Moonlight is; see [LICENSE.txt](LICENSE.txt) and [NOTICE](NOTICE), which lists what StrmLight changed and the fonts and artwork it bundles.

StrmLight is built on Moonlight for Android by [Cameron Gutman](https://github.com/cgutman), [Diego Waxemberg](https://github.com/dwaxemberg), [Aaron Neyer](https://github.com/Aaronneyer), [Andrew Hennessy](https://github.com/yetanothername) and its contributors. Moonlight is the work of students at [Case Western](http://case.edu) and was started as a project at [MHacks](http://mhacks.org). If you want to stream games rather than StrmQt, use [Moonlight](https://moonlight-stream.org).
