# Changelog

All notable changes to this project will be documented in this file.

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
