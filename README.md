# Camera Application

A high-performance Android camera application featuring advanced capture modes and manual controls. This project demonstrates the integration of CameraX and Camera2 APIs through a hybrid engine architecture.

## Features

- Photo and Video Capture: Standard media capture using CameraX.
- Night Sight: Multi-frame burst stacking for improved low-light performance.
- HDR: Exposure bracketing and fusion for high dynamic range photography.
- Portrait Mode: AI-powered background segmentation and bokeh effects.
- Pro Mode: Manual control over ISO, Shutter Speed, and Focus using Camera2 interop.
- Real-time HUD: Rule of thirds grid and horizon leveler.

## Architecture

The app uses a Hybrid Capture Engine that dynamically switches between CameraX (for standard tasks and stability) and Camera2 (for specialized burst modes and manual hardware control).

## Getting Started

1. Clone the repository.
2. Open in Android Studio (Ladybug or newer).
3. Build and deploy to a device running Android 12 (API 31) or higher.
