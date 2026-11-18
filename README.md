# Camera

A free and open-source Android camera app with computational photography that runs entirely on-device. No Google Play Services, no proprietary libraries, no analytics and no network access.

## Features

- **Photo**: GPU pixel binning for clean ~12 MP shots, or full sensor resolution.
- **Night**: AE/AF warm-up, then a burst of longer manual exposures that are aligned (MTB) and averaged, with a shadow-lifting tone map.
- **HDR** (photo option): deterministic -2/0/+2 EV manual exposure bracket, aligned and merged with Mertens exposure fusion.
- **Long exposure**: aligned multi-frame average for silky water and light trails, handheld.
- **Time lapse**: speeds up 15x (one frame every half second, played at 30 fps).
- **Portrait**: the framework face detector seeds an OpenCV GrabCut segmentation, and the background gets a halo-free blur.
- **Panorama**: tap and sweep; image-tracked cylindrical capture with a live 360° preview strip and level guide, auto-stop at a full circle, and a native C++ stitcher (focal self-calibration, pairwise refinement, drift levelling, gain matching, min-cost seams, multi-band blending).
- **Video** with audio, pause/resume and snapshots. **QuickTake**: long-press the shutter in Photo mode.
- **Slow motion** on devices with constrained high-speed capture (files play back in slow motion in any player).
- **Manual controls**: ISO and shutter (logarithmic sliders), manual focus, white-balance presets. Applied live without restarting the camera.
- **Brightness slider**: tap to focus, then drag the sun icon up or down.
- **Video options**: 1080p or 4K, stabilization, torch.
- **Macro focus** mode.
- **RAW + JPEG** (DNG) capture.
- **Palm timer**: hold up an open hand next to your face to start a 3-second countdown (no ML models).
- Grid overlays (3×3, 4×4, golden ratio), horizon leveler, pinch to zoom with live zoom readout, double-tap to switch camera, volume-key shutter, one-tap flash Off/Auto/On.
- Controls rotate with the device in landscape.
- QR code suggestions (decoded on-device) and a low-light prompt to switch to Night.
- Volume keys as shutter, zoom or volume; video frame rate (30/60 fps) and audio toggle.
- Optional geotagging through the framework `LocationManager`.
- Works as the lock-screen camera.

## Architecture

Compose UI -> `CameraViewModel` (StateFlow) -> `HybridCaptureEngine`, which routes to:
- `CameraXProvider`: preview, standard/binned/portrait photos, video, panorama, palm-timer analysis.
- `Camera2Provider`: Night, HDR, RAW and slow motion. CameraX is released for these and restored afterwards.

Native code (`app/src/main/cpp`) uses OpenCV from the official Maven Central AAR via prefab.

## License

Copyright (C) 2026 the Camera contributors.

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE).

## Dependencies & licenses

All runtime dependencies are free software:

| Dependency | License |
|---|---|
| AndroidX (Compose, CameraX, DataStore, Lifecycle) | Apache-2.0 |
| OpenCV 4.12 (`org.opencv:opencv`, Maven Central) | Apache-2.0 |
| Kotlin stdlib / coroutines | Apache-2.0 |

## Building

1. Open in Android Studio (Ladybug or newer) with the NDK and CMake 3.22.1 installed.
2. Build and deploy to a device running Android 12 (API 31) or higher.

The universal APK includes all four ABIs (OpenCV makes it large). To build a single-ABI APK with its own version code (`base * 10 + n`):

```
./gradlew assembleRelease -PabiFilter=arm64-v8a   # n: armeabi-v7a=1, arm64-v8a=2, x86=3, x86_64=4
```

Store listing metadata for F-Droid lives in `fastlane/metadata/android/`.
