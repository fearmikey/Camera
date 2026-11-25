# Camera

A free and open-source Android camera app with computational photography that runs entirely on-device. No Google Play Services, no proprietary libraries, no analytics and no network access.

## Features

- **Photo**: GPU pixel binning for clean ~12 MP shots, or full sensor resolution.
- **Night**: long-exposure metering, then a burst of 8–20 hand-holdable manual exposures (up to 1/8 s each with OIS, 1/15 s without) that are aligned to sub-pixel precision and averaged, then brightened by up to 5 EV in linear light to a bright, contrasty night-mode look (measured black point, highlights rolled off in their own color, contrast curve), cleaned of chroma noise, sharpened and dithered from a 16-bit merge (no banding). **Auto Night** (flash setting) uses it automatically for Photo-mode shots in the dark.
- **HDR** (photo option): deterministic -2/0/+2 EV manual exposure bracket, aligned and merged with Mertens exposure fusion.
- **Long exposure**: handheld, ~2 s of back-to-back base-ISO frames (16–32) aligned and averaged in linear light for silky water, with bright light trails recovered from clipped highlights.
- **Time lapse**: speeds up 15x (one frame every half second, played at 30 fps).
- **Portrait**: starts at a flattering 2x on the rear camera (1x/2x, pinch 1–3x) and at the full selfie view on the front (.7x/1x), live face brackets and framing hints in the viewfinder, face-priority focus/exposure, and an adjustable blur (ƒ/1.4–ƒ/16). Every face seeds an OpenCV GrabCut segmentation, a guided filter snaps the cut-out to hair and edges, and the background gets a lens-style disc blur with glowing highlights and matched grain. Optionally keeps the unblurred original.
- **Panorama**: tap and sweep; image-tracked cylindrical capture with a live 360° preview strip and level guide, auto-stop at a full circle, and a native C++ stitcher (focal self-calibration, pairwise refinement, drift leveling, gain matching, min-cost seams, multi-band blending).
- **Video** with audio, pause/resume and snapshots. **QuickTake**: long-press the shutter in Photo mode.
- **Slow motion** on devices with constrained high-speed capture: 1/4x (120 fps) or 1/8x (240 fps) where supported, with a live viewfinder while recording; files play back in slow motion in any player.
- **Manual controls**: a tool row with exposure compensation, ISO and shutter (logarithmic dials), manual focus and white-balance presets; each tool opens a sliding tick dial above the shutter. Applied live without restarting the camera.
- **Brightness slider**: tap to focus, then drag the sun icon up or down.
- **Video options**: 1080p or 4K, stabilization, torch.
- **Macro focus** mode.
- **RAW + JPEG** (DNG) capture.
- **Palm timer**: hold up an open hand next to your face to start a 3-second countdown (no ML models).
- Zoom buttons that match the phone's actual lenses (e.g. .5 / 1x / 2x / 5x), with smooth glides between them.
- Grid overlays (3×3, 4×4, golden ratio), horizon leveler (landscape) and top-down leveler, pinch to zoom with live zoom readout, double-tap to switch camera, volume-key shutter, one-tap flash Auto Night / Auto flash / On / Off.
- Controls rotate with the device in landscape.
- **Code scanner** (on-device): QR codes and EAN/UPC barcodes, several at once, outlined live in the viewfinder. Smart actions join Wi-Fi, add contacts or calendar events, write email/SMS, dial or open maps. Can also scan a code from an image.
- Low-light prompt to switch to Night (unless Auto Night is on, which handles it automatically).
- Volume keys as shutter, zoom or volume; video frame rate (30/60 fps) and audio toggle.
- Optional geotagging through the framework `LocationManager`.
- Works as the lock-screen camera.

## Architecture

Compose UI -> `CameraViewModel` (StateFlow) -> `HybridCaptureEngine`, which routes to:
- `CameraXProvider`: preview, standard/binned/portrait photos, video, panorama, palm-timer analysis.
- `Camera2Provider`: Night, HDR, RAW and slow motion. CameraX is released for these and restored afterwards.

Native code (`app/src/main/cpp`) uses OpenCV from the official Maven Central AAR via prefab.

## Support

Camera is free, has no ads and never will. If you enjoy it, you can support development at [buymeacoffee.com/ximw7nxi1j](https://buymeacoffee.com/ximw7nxi1j). Bug reports and feature requests are welcome in the [issue tracker](https://github.com/fearmikey/Camera/issues).

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

### Releases (reproducible builds)

F-Droid builds each release tag from source and publishes it with the developer's signature only if its APK is byte-identical to the signed one on the GitHub release. To build those APKs, tag the release, then run:

```
scripts/build-release.sh v1.2.4 /path/to/Camera-Release.jks
```

The script clones the tag fresh and builds each ABI clean (the way F-Droid does), signs the APKs, and writes `dist/<tag>/Camera-<versionName>-<abi>.apk` (e.g. `Camera-1.2.4-arm64-v8a.apk`; most phones want `arm64-v8a`). Upload all four to the GitHub release for that tag.
