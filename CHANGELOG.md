# Changelog

All notable changes to this project will be documented in this file.

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
