# Camera Application

A high-performance Android camera application featuring advanced capture modes and manual controls. This project demonstrates the integration of CameraX and Camera2 APIs through a hybrid engine architecture.

## Features

- Photo and Video Capture: Standard media capture using CameraX.
- Redesigned UI: Modern interface inspired by Google Camera with intuitive mode and zoom switching.
- Dynamic Zoom: Automatic detection of hardware zoom capabilities and physical lens presets.
- Smart Rotation: Control icons rotate based on device orientation while maintaining stable preview.
- Night Sight: Multi-frame burst stacking for improved low-light performance.
- HDR: Exposure bracketing and fusion for high dynamic range photography.
- Portrait Mode: AI-powered background segmentation and bokeh effects.
- Pro Mode: Manual control over ISO, Shutter Speed, and Focus using Camera2 interop.
- GPU Processing: High-performance image processing pipeline using custom GLSL shaders for effects like pixel binning and Night Sight.
- Gesture Controls: Advanced gesture-based interactions including pinch-to-zoom, double-tap to switch, and long-press to lock AF/AE.
- Modern Aesthetic: Updated iconography and launcher assets designed for high-density displays.
- Persistent Settings: Integrated Jetpack DataStore for a comprehensive and stable settings management system.
- Real-time HUD: Rule of thirds grid and horizon leveler.

## Architecture

The app uses a Hybrid Capture Engine that dynamically switches between CameraX (for standard tasks and stability) and Camera2 (for specialized burst modes and manual hardware control).

## Getting Started

1. Clone the repository.
2. Open in Android Studio (Ladybug or newer).
3. Build and deploy to a device running Android 12 (API 31) or higher.
