# Changelog

All notable changes to this project will be documented in this file.

## [1.1.0] - 2026-11-18

### Added
- System theming: Material You dynamic colours from the wallpaper and automatic light/dark (`ui/theme/Theme.kt`). Settings and the quick-settings sheet follow the system theme; the viewfinder stays dark but uses the system accent.
- Proper monochrome launcher icon for Android 13+ themed icons.
- Long exposure mode (aligned multi-frame average that blurs moving water/traffic).
- Time lapse video mode (Camera2 + MediaRecorder, 2 fps capture played at 30 fps).
- On-device QR code suggestions (OpenCV QRCodeDetector): open links/phone/email/maps or copy text.
- Low-light suggestion chip that offers Night mode in dark scenes.
- HDR as a photo option (quick settings → More, and Settings → Photo) instead of a separate mode.
- Video frame rate (Auto/30/60 fps when supported) and Record audio options.
- Volume key action: Shutter, Zoom or Volume (replaces the on/off volume-shutter toggle; old value migrated).
- Double-tap-to-switch and low-light suggestion toggles; Device storage info in Settings.
- Licensed under GPL-3.0-or-later (`LICENSE`).
- Brightness (exposure compensation) slider next to the tap-to-focus ring.
- One-tap flash toggle in the top bar; flash "On" acts as a torch in video.
- Video settings: 1080p/4K resolution and stabilization toggle (new `videoStabilization` preference).
- Macro focus mode (AF macro) with top-bar indicator.
- White-balance presets and real hardware ranges in manual controls.
- Double-tap the viewfinder to switch cameras.
- Permission screen with "Allow" and "Open settings" actions.
- F-Droid readiness: fastlane metadata (`fastlane/metadata/android/en-US`), APK dependency-info blob disabled, optional per-ABI builds via `-PabiFilter=<abi>`.
- Audio recording for videos (RECORD_AUDIO requested at first launch; optional).
- Working photo/video geotagging using the framework LocationManager (no Play Services).
- EXIF (date, make/model, GPS) on all processed images; images are written as pending MediaStore entries.
- HDR mode exposed in the photo mode list.
- RAW + JPEG save mode now captures a DNG to DCIM/Camera via Camera2.
- QuickTake: long-press the shutter in Photo/Portrait to record a clip.
- About section in Settings.

### Improved
- Mode rows now: Night · Portrait · Photo · Long exposure · Panorama, and Slow motion · Time lapse · Video (HDR removed from the row). Mode lists/labels centralised in `CameraModes`.
- Settings reorganised into General / Composition / Fast access / Photo / Video / Advanced / About; quick-settings timer is Off/3/10 s.
- Volume-key auto-repeat no longer fires bursts of shots.
- Layout: viewfinder anchored under the top bar so the shutter row never overlaps it; tighter bottom controls; centred mode carousel; live zoom value in the zoom pill; control icons rotate with the device; duplicate top flip button removed.
- Manual controls redesigned (logarithmic ISO/shutter, Auto chips, Reset) and applied live instead of rebinding the camera on every slider move.
- Full-screen processing overlay only for Night/HDR/Portrait; quick photos show a spinner on the thumbnail.
- Settings sheet split into Photo / Video / More tabs.
- Grid button cycles through all grid types.
- Removed all Google/proprietary dependencies: MediaPipe (pulled Firebase telemetry), TensorFlow Lite, Coil/OkHttp, Material Components, AppCompat, unused Hilt/KSP entries.
- OpenCV now comes from the official Maven Central AAR (prefab) instead of a 962 MB vendored prebuilt SDK.
- Night mode rewritten: AE/AF warm-up, manual longer-exposure burst, MTB alignment, temporal averaging and shadow-lifting tone map (native OpenCV).
- HDR rewritten: deterministic manual exposure bracket (-2/0/+2 EV) fused with MTB alignment + Mertens exposure fusion.
- Portrait rewritten: framework face detection seeds OpenCV GrabCut; halo-free background blur.
- Palm timer rewritten without ML models: face-calibrated skin segmentation + convexity-defect finger detection.
- Panorama UI redone to match Google Camera: tap the shutter and pause briefly on the dots (a frame is only taken after holding the dot in the circle for ~0.4 s; panning past a dot points you back to it), which gives sharp, level frames, yellow guide line and direction arrow, live corner preview that grows into the panorama, and a minimal capture screen with just the stop button.
- Panorama rebuilt from scratch: tap once and sweep left or right. Frames are projected onto a cylinder and tracked by image content (no sensor dots), keyframes are picked automatically, exposure/white balance are locked for the sweep, and the focal length self-calibrates when lens metadata is wrong. Live 360° preview strip, level guide and hints (slow down / tilt / lost track); stops automatically at a full circle. Stitching refines every pair at half resolution, levels drift, matches exposure, cuts min-cost seams with local multi-band blending and crops to a clean rectangle, at the full analysis resolution (~1600 px tall) instead of an upscaled 0.4× mosaic.
- Slow motion now encodes at 30 fps for true slow-motion playback and writes straight to MediaStore.
- Flash control is now a real Off / Auto / On setting.
- Settings cleaned of non-functional placeholders; Palm timer is off by default.

### Fixed
- Panorama: the native engine was freed after the first panorama but still used by the analyzer for the next one (use-after-free).
- Pinch-zoom started from a stale zoom value after tapping a zoom preset.
- Manual focus slider could get an empty range (hard-coded capabilities).
- Preview froze after a Night/HDR capture (CameraX was never rebound).
- Night mode saved a 1x1 image and destroyed the shared EGL context, breaking 12 MP binning afterwards.
- Portrait never blurred the background (opaque mask composited with SRC_IN; segmentation model was a 0-byte file).
- Photos/videos taken in landscape were saved in portrait orientation.
- Top flip-camera button, manual-controls button and thumbnail tap did nothing.
- Zoom presets/capabilities were read before the camera finished binding.
- Sensors stayed registered while the app was in the background.
- YUV conversion ignored row strides (corrupted frames on some devices).
- Slow-mo restore used a destroyed handler and conflicted with CameraX holding the camera.

## [1.0.7] - 2026-08-02

### Improved
- Lifecycle Management: Refined CameraX and Camera2 providers for more reliable engine transitions and resource cleanup.
- Capture Engine: Enhanced the Hybrid Capture Engine logic to better handle edge cases during rapid mode switching and initialization.
- UI/UX Stability: Updated HudOverlay and CameraViewModel to ensure smoother UI state synchronization and robust error handling.
- Performance: Optimized resource allocation in the capture pipeline to reduce latency during consecutive photo captures.

## [1.0.6] - 2026-08-02

### Improved
- Panorama Stitching: Significant optimizations to the native C++ engine for faster processing and better seam blending.
- Real-time Guidance: Refined HUD feedback for panorama capture, providing more accurate alignment indicators.
- Memory Management: Enhanced frame buffer handling in the Native Bridge to reduce peak memory usage during large panorama captures.
- Stability: Improved error recovery and state synchronization between the UI and native engine.

## [1.0.5] - 2026-08-02

### Added
- Native Panorama Engine: Implemented high-performance C++ panorama stitching using OpenCV via JNI.
- Panorama Mode: New capture mode with real-time frame alignment and guidance in the HUD.
- Native Bridge: Introduced NativePanoramaBridge for efficient data transfer between Kotlin and C++.

### Improved
- Frame Processing: Implemented frameskip optimization in GpuImageProcessor to maintain high preview frame rates during heavy processing.
- UI Feedback: Added dedicated panorama progress and alignment indicators to HudOverlay.
- Settings: Expanded user preferences to include panorama quality and stitching options.

## [1.0.4] - 2026-08-02

### Added
- Gesture Result Model: Introduced GestureResult to standardize interaction reporting between the analyzer and ViewModel.
- Modern Icon Set: Added a new modern camera icon and updated launcher assets for a fresher visual identity.

### Improved
- Advanced Gesture Support: GestureAnalyzer now handles double-taps for camera switching and long-presses for AF/AE locking.
- Portrait Segmentation: Optimized the PortraitSegmenter pipeline for reduced latency and better edge detection.
- HUD Feedback: HudOverlay now includes dynamic visual indicators for gesture interactions and lock states.
- Engine Stability: Refined CameraX and Camera2 providers to ensure consistent state transitions and error recovery.

### Fixed
- Asset Handling: Updated build configuration to prevent compression of TFLite and task models, ensuring faster loading.

## [1.0.3] - 2026-08-02

### Added
- GPU Image Processing: Implemented a high-performance GLSL-based processing pipeline including pixel binning and multi-frame alignment.
- Advanced Shaders: Added custom shaders for night sight merging and passthrough rendering.
- Settings Infrastructure: Introduced SettingsActivity and integrated Jetpack DataStore for persistent user preferences.
- Gesture Analysis: Added dedicated gesture analyzer for intuitive camera control interactions.
- Streaming Frame Buffer: Implemented a new buffer management system for continuous frame processing.

### Improved
- Hybrid Capture Engine: Refined engine switching logic for better stability and performance.
- UI/UX: Updated HudOverlay and SettingsSheet for better accessibility and visual clarity.
- Portrait Mode: Enhanced AI-powered background segmentation and bokeh processing.

## [1.0.2] - 2026-08-01

### Added
- Redesigned UI/UX: Completely overhauled the interface to match Google Camera App aesthetic.
- Smart Icon Rotation: Added sensor-based icon rotation that keeps controls upright while locked in portrait mode.
- Dynamic Zoom: Implemented hardware-aware zoom switcher with presets detected from physical camera capabilities.
- Auto Leveler: Horizon leveler now automatically appears when the device is rotated to landscape.

### Fixed
- Build Optimization: Resolved Kotlin daemon crash and enabled incremental compilation for faster builds.
- Gradle Modernization: Upgraded AGP to 8.7.3 and migrated to Kotlin 2.0.21 with the new Compose compiler plugin.
- Clean Code: Fixed multiple lint and compiler warnings across the codebase.

## [1.0.1] - 2026-08-01

### Fixed
- Autofocus: Implemented proper AF/AE metering flags and auto-cancel duration.
- Thumbnails: Fixed UI update logic to show the latest captured media immediately.
- Video Mode: Fixed shutter button appearance and implemented actual video recording in CameraX.
- Night Mode: Resolved a crash by synchronizing burst frame capture and background processing.
- Pro Mode: Fixed preview freeze by migrating manual configuration to CameraX-Camera2 interop.

## [1.0.0] - 2026-07-15
- Initial release with Hybrid Capture Engine.
- Basic support for Photo, Video, Night, and HDR modes.
