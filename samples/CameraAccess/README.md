# CameraAccess Oakley LiveKit Streaming

This fork of Meta's `CameraAccess` sample keeps the normal Oakley Meta / Meta Wearables DAT camera preview on the Android phone and also publishes the same camera frames to a LiveKit room as a custom WebRTC video track.

The video path is:

```text
Oakley Meta glasses
  -> Android CameraAccess app DAT preview
  -> LiveKit custom video track: oakley-meta-camera
  -> LiveKit Docker room on your computer
  -> Browser viewer on your computer
```

The local phone preview does not depend on LiveKit. If the phone preview is blank, debug the DAT/glasses stream first. LiveKit only receives frames after the stream is already producing DAT `VideoFrame`s.

## Repositories

This workflow uses two local repositories:

- Android app fork: `MilanOnTheMoon/meta-wearables-dat-android`
- LiveKit host fork: `MilanOnTheMoon/field-ai-bridge-oakley`

Expected local paths in Milan's setup:

```bash
/Users/mil/Documents/CalTech Firefighting Project/meta-wearables-dat-android
/Users/mil/Documents/CalTech Firefighting Project/fieldai-bridge
```

If your paths differ, substitute your own paths in the commands below.

## What Was Added

The Android app now includes:

- `LiveKitPublisher`, which owns the LiveKit `Room`, `VideoFrameCapturer`, and `LocalVideoTrack`.
- DAT I420 `VideoFrame` to WebRTC `JavaI420Buffer` conversion.
- A LiveKit overlay on the camera screen with:
  - LiveKit URL input
  - Fetch Token button
  - Connect button
  - Stop LiveKit button
  - status text
- A custom LiveKit video track named `oakley-meta-camera`.

The LiveKit host repo now includes helper scripts:

- `livekit_bridge/run_livekit_room.sh`
- `livekit_bridge/stop_livekit_room.sh`
- `livekit_bridge/run_livekit_viewer.sh`
- `livekit_bridge/stop_livekit_viewer.sh`
- `livekit_bridge/mint_android_token.sh`

The viewer container also serves a development token endpoint:

```text
http://<computer-lan-ip>:8080/token
```

The Android app's **Fetch Token** button calls that endpoint automatically.

## Prerequisites

### Hardware

- Android phone paired with the Oakley Meta / Meta glasses.
- Oakley Meta / Meta glasses with camera support.
- Computer running Docker Desktop.
- Phone and computer on the same Wi-Fi / LAN.

### Phone / Meta Setup

1. Install and sign into the Meta AI app.
2. Pair the glasses with the phone.
3. Enable Developer Mode for the glasses in the Meta AI app.
4. Make sure the glasses are connected, open/unfolded, worn or otherwise stream-ready, and charged.

### Android Development Setup

You need:

- Android Studio
- Android SDK 31+
- JDK supported by Android Studio
- GitHub package token for Meta Wearables DAT SDK dependencies

The sample reads the Meta package token from either:

```bash
GITHUB_TOKEN
```

or `samples/CameraAccess/local.properties`:

```properties
github_token=YOUR_GITHUB_TOKEN_WITH_READ_PACKAGES
```

## Build And Install The Android App

From the Android repo:

```bash
cd /Users/mil/Documents/CalTech\ Firefighting\ Project/meta-wearables-dat-android/samples/CameraAccess
./gradlew :app:assembleDebug
```

Then install from Android Studio, or use `adb`:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch, allow:

- Camera
- Nearby devices / Bluetooth

`INTERNET` is already declared in the manifest and does not appear as an Android runtime permission.

## Start LiveKit With Docker

From the LiveKit host repo:

```bash
cd /Users/mil/Documents/CalTech\ Firefighting\ Project/fieldai-bridge/livekit_bridge
./run_livekit_room.sh
```

The script will:

1. Build the `ff-livekit-room` Docker image.
2. Auto-detect your computer's LAN IP.
3. Stop/remove any old `ff-livekit-room` container.
4. Start LiveKit with:
   - `7880/tcp` for WebSocket signaling
   - `7881/tcp` for WebRTC TCP fallback
   - `7882/udp` for WebRTC media
5. Print the Android URL.

Example output:

```text
LiveKit is running.
Android URL: ws://10.0.0.182:7880
Room: test-room
Dev API key/secret: devkey / secret
```

Copy the `Android URL`; you will enter it into the Android app.

If auto-detection chooses the wrong IP, specify it manually:

```bash
LIVEKIT_NODE_IP=10.0.0.182 ./run_livekit_room.sh
```

## Start The Browser Viewer

In the same LiveKit host repo:

```bash
cd /Users/mil/Documents/CalTech\ Firefighting\ Project/fieldai-bridge/livekit_bridge
./run_livekit_viewer.sh
```

The script will:

1. Build the `ff-livekit-viewer` Docker image.
2. Stop/remove any old `ff-livekit-viewer` container.
3. Start the viewer on port `8080`.
4. Point the viewer at the same LiveKit room.
5. Expose the `/token` endpoint used by the Android app.

Open the viewer on your computer:

```text
http://localhost:8080/
```

You can also open it from another device on the LAN:

```text
http://<computer-lan-ip>:8080/
```

Expected initial viewer state:

```text
connected, waiting for video...
```

That means the browser joined LiveKit successfully and is waiting for the Android publisher.

## Connect From The Android App

1. Launch `CameraAccess` on the Android phone.
2. Register/connect the app through the Meta AI / DAT flow if prompted.
3. Select the glasses and start the camera stream.
4. Confirm the phone preview appears.
5. In the LiveKit URL field, enter the URL printed by `run_livekit_room.sh`, for example:

```text
ws://10.0.0.182:7880
```

6. Tap **Token**.

The app will fetch a publisher token from:

```text
http://10.0.0.182:8080/token?room=test-room&identity=android-oakley
```

7. Tap **Connect**.
8. Watch the browser viewer.

Expected result:

- The phone continues showing the Oakley camera preview.
- Android status changes to `LiveKit connected`.
- Logcat shows LiveKit frame publishing every 30 frames.
- The browser viewer switches from waiting to streaming.
- The remote video track is published as `oakley-meta-camera`.

## Daily Run Checklist

Computer:

```bash
cd /Users/mil/Documents/CalTech\ Firefighting\ Project/fieldai-bridge/livekit_bridge
./run_livekit_room.sh
./run_livekit_viewer.sh
open http://localhost:8080/
```

Phone:

1. Open `CameraAccess`.
2. Start the glasses stream.
3. Enter `ws://<computer-lan-ip>:7880`.
4. Tap **Token**.
5. Tap **Connect**.

## Stop LiveKit

Stop the room:

```bash
cd /Users/mil/Documents/CalTech\ Firefighting\ Project/fieldai-bridge/livekit_bridge
./stop_livekit_room.sh
```

Stop the viewer:

```bash
./stop_livekit_viewer.sh
```

Or stop both directly:

```bash
docker stop ff-livekit-room ff-livekit-viewer
docker rm ff-livekit-room ff-livekit-viewer
```

## Token Details

Development defaults:

```text
Room: test-room
Android publisher identity: android-oakley
Viewer identity: viewer
API key: devkey
API secret: secret
```

Normally, use the Android **Token** button.

If you need a token manually:

```bash
cd /Users/mil/Documents/CalTech\ Firefighting\ Project/fieldai-bridge
./livekit_bridge/mint_android_token.sh
```

That prints a JWT and copies it to your Mac clipboard when `pbcopy` is available.

For production, do not ship `devkey` / `secret` or mint tokens from the viewer. Replace the dev endpoint with a real backend token endpoint.

## Troubleshooting

### Browser says connected, waiting for video

This is good for the viewer, but Android is not publishing yet.

Check:

- Android app has phone preview.
- LiveKit URL in Android is `ws://<computer-lan-ip>:7880`, not `ws://localhost:7880`.
- You tapped **Token** successfully.
- You tapped **Connect**.
- Phone and computer are on the same Wi-Fi.

### Android token fetch fails

The Android app derives the token endpoint from the LiveKit URL.

If LiveKit URL is:

```text
ws://10.0.0.182:7880
```

Then token fetch uses:

```text
http://10.0.0.182:8080/token
```

Check:

```bash
docker ps
```

You should see both:

```text
ff-livekit-room
ff-livekit-viewer
```

From your computer, test:

```bash
curl http://localhost:8080/token
```

If the phone cannot fetch it, confirm the phone can reach:

```text
http://10.0.0.182:8080/
```

from the phone browser.

### Android connects but viewer stays blank

Check Android Logcat for:

```text
LiveKitPublisher
CameraAccess:StreamViewModel
```

Expected publish log every 30 frames:

```text
Published 30 LiveKit frames
```

If you never see publish logs, the phone is not receiving DAT frames or LiveKit is not connected.

### Phone preview is blank

LiveKit is not required for phone preview. Debug DAT first:

- Are the glasses connected in the Meta AI app?
- Is Developer Mode enabled?
- Did you grant Camera and Nearby devices permissions?
- Did Meta AI grant camera permission to the app?
- Is the stream state reaching `STREAMING`?

Relevant logs:

```text
CameraAccess:StreamViewModel
Stream state changed
Collecting video frames from stream
Failed to convert YUV to bitmap
```

### Android says allow Bluetooth / connect to internet

This fork no longer asks Android for `INTERNET` as a runtime permission. If you still see the old message, reinstall the latest build.

Manually check permissions:

```text
Android Settings -> Apps -> CameraAccess -> Permissions
```

Allow:

- Camera
- Nearby devices / Bluetooth

### `ws://localhost:7880` does not work on Android

On the phone, `localhost` means the phone itself. Use your computer's LAN IP:

```text
ws://10.0.0.182:7880
```

### Video has high latency

Current latency-reduction choices:

- LiveKit publishes before local bitmap conversion.
- LiveKit simulcast is disabled for the custom camera track.
- The source track uses the DAT medium stream dimensions.

If latency is still too high, try lowering the DAT stream configuration in `StreamViewModel`:

```kotlin
StreamConfiguration(videoQuality = VideoQuality.LOW, 15)
```

That reduces Bluetooth payload, encoding work, and network pressure.

### Colors look wrong in the browser

The Android conversion assumes DAT I420 buffer layout:

```text
Y plane, then U plane, then V plane
```

If the viewer looks purple/green, test swapped U/V in `LiveKitPublisher`.

## Useful Commands

Check running containers:

```bash
docker ps
```

Room logs:

```bash
docker logs -f ff-livekit-room
```

Viewer logs:

```bash
docker logs -f ff-livekit-viewer
```

Rebuild Android:

```bash
cd /Users/mil/Documents/CalTech\ Firefighting\ Project/meta-wearables-dat-android/samples/CameraAccess
./gradlew :app:assembleDebug
```

Compile check:

```bash
./gradlew :app:compileDebugKotlin
```

## Implementation Notes

Important Android files:

- `app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/LiveKitPublisher.kt`
- `app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/StreamViewModel.kt`
- `app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/ui/StreamScreen.kt`
- `app/src/main/AndroidManifest.xml`

Important LiveKit host files:

- `livekit_bridge/run_livekit_room.sh`
- `livekit_bridge/run_livekit_viewer.sh`
- `livekit_bridge/viewer_livekit/main.py`

The Android app does not encode MP4, does not send raw frames over REST, and does not replace the DAT preview with a LiveKit preview. WebRTC/LiveKit handles encoding and transport.

## License

This source code is licensed under the license found in the `LICENSE` file in the root directory of this repository.
