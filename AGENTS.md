# Camera - Guidelines for AI Agents & Developers

## AGENT WORKFLOW & CODE MAP (.agent_manifest.json)

### MANDATORY CODE MAP CONSULTATION
Before attempting any code modification, agents **MUST** consult `.agent_manifest.json` to identify the architectural pattern, data flow, capture-mode routing, and specific files responsible for the feature. Do not guess file paths.

### MANIFEST MUST BE UPDATED WITH EVERY CHANGE
Any change to the app **MUST** include a matching update to `.agent_manifest.json` in the same change (same edit session and same commit). This applies whenever you:
* Add, rename, move, or delete a source file, asset, shader, model, or native (C++/JNI) file.
* Add, remove, or change a feature, camera mode, capture route, preference, or UI screen/overlay.
* Change data flow, engine routing (`HybridCaptureEngine`), the `CaptureEngine` interface, or DI/object-graph construction.
* Change build configuration (Gradle, version catalog, CMake, SDK levels, dependencies) or the app version.
* Fix or introduce an item listed under `known_gaps_and_stubs`.

When updating, also bump `manifest_meta.last_updated` (and `manifest_meta.app_version` on releases). Keep paths repo-relative and accurate. A change is not complete until the manifest reflects it. If a change truly has no effect on the map (e.g., a pure logic fix inside an existing function), still bump `last_updated` and update any description that changed.

### GIT COMMIT ATTRIBUTION
AI co-author trailers (e.g., `Co-Authored-By:`) are permitted in commit messages.

## PROJECT CONVENTIONS

* **Architecture:** Compose UI -> `CameraViewModel` (StateFlow) -> `CaptureEngine` (`HybridCaptureEngine` routing to `CameraXProvider` / `Camera2Provider`). New engine capabilities go on the `CaptureEngine` interface and must be implemented in all three engine classes.
* **Dependency wiring:** Manual construction in `MainActivity.onCreate`. Hilt is not active (`core/di/CameraModule.kt` is commented out).
* **Camera modes:** When adding a mode, update `CameraMode.kt`, both mode lists in `MainActivity.kt` (`onModeSwiped` and `CameraControls`), the label in `CameraModeSelector`, and the routing in `HybridCaptureEngine` / providers.
* **Preferences:** New settings need a field in `UserPreferences.kt`, a key + flow mapping + `update*` function in `UserPreferencesRepository.kt`, a ViewModel method, and UI wiring.
* **Native code:** JNI symbols are bound to `com.fearmikey.camera.data.engine.processing.NativePanoramaBridge`. OpenCV is vendored under `opencv/` and passed via `-DOpenCV_DIR` in `app/build.gradle.kts`.
* **Assets:** `.tflite` and `.task` models must stay uncompressed (`androidResources.noCompress`).

## RELEASE WORKFLOW

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add a `## [X.Y.Z] - YYYY-MM-DD` section to `CHANGELOG.md` (Added / Improved / Fixed).
3. Update `README.md` features if user-visible behavior changed.
4. Update `.agent_manifest.json` (`manifest_meta.app_version`, `last_updated`, and any affected entries).
