# Saath

Turn your friends' phones and laptops into one speaker. Start a party on an Android phone; friends scan a QR code, the party page opens in their browser, and every device plays the same music in sync. Nothing to install for listeners, and no cloud.

## How it works

- **The phone is the server.** The app runs a small web server (`HttpServer`) and serves the listener page from its own assets, so it works with no internet.
- **Online mode:** everyone on the same Wi-Fi. **Offline mode:** the phone makes a local-only hotspot (or you use your normal hotspot).
- **My Music (v1):** songs from the phone's library are decoded and streamed as timestamped 40 ms chunks. **Any App (v2):** Android's playback capture copies what Spotify, YouTube Music and similar apps play.
- **Sync:** each browser syncs its clock to the host (NTP-style, best round trips only), maps host time to its audio clock with `getOutputTimestamp`, and schedules every chunk sample-accurately. **Tune up** then plays a chirp on each device; the host's microphone finds each chirp by FFT cross-correlation and corrects each device, then measures again to verify.
- **Updates:** every push to `main` runs the tests, builds the APK, runs it on an Android emulator with three real browsers joining, and publishes a GitHub release. The app checks `releases/latest/download/version.json` and installs newer builds itself.

## Layout

| Path | What |
| --- | --- |
| `android/app/src/main/java/com/saath/core` | Platform-free core: party hub, HTTP/WebSocket server, chunk format, chirp detection, streamer |
| `android/app/src/main/java/com/saath/app` | Android: activity + WebView UI bridge, foreground service, decoder, capture, hotspot, host speaker, mic, updater |
| `android/app/src/main/assets/web` | Listener page (served by the phone) |
| `android/app/src/main/assets/host` | Host app UI |
| `tools/desktop` | Desktop host and core tests (plain Java) |
| `tools/e2e` | Headless-browser end-to-end test |
| `site` | Download page on Vercel (`/apk` redirects to the latest APK) |

## Run the tests

```bash
./tools/test-core.sh                      # core unit tests (JDK 11+)
pip install playwright && python -m playwright install chromium
python tools/e2e/e2e_listeners.py         # three browser listeners against the desktop host
```

The release keystore is committed on purpose so CI can sign every update with the same key; keep the update URL under your control.
