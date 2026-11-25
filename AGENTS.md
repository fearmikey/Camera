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
Do **NOT** add AI attribution to commits or PRs: no `Co-Authored-By: Claude ...` (or any AI co-author) trailers and no "Generated with Claude Code" lines. Commits are authored by the repository owner only.

## PROJECT CONVENTIONS

* **Architecture:** Compose UI -> `CameraViewModel` (StateFlow) -> `CaptureEngine` (`HybridCaptureEngine` routing to `CameraXProvider` / `Camera2Provider`). New engine capabilities go on the `CaptureEngine` interface and must be implemented in all three engine classes.
* **Dependency wiring:** Manual construction in `MainActivity.onCreate`. Hilt is not active (`core/di/CameraModule.kt` is commented out).
* **Camera modes:** When adding a mode, update `CameraMode.kt`, both mode lists in `MainActivity.kt` (`onModeSwiped` and `CameraControls`), the label in `CameraModeSelector`, and the routing in `HybridCaptureEngine` / providers.
* **Preferences:** New settings need a field in `UserPreferences.kt`, a key + flow mapping + `update*` function in `UserPreferencesRepository.kt`, a ViewModel method, and UI wiring.
* **Native code:** JNI symbols are bound to `com.fearmikey.camera.data.engine.processing.NativePanoramaBridge` and `NativeVisionBridge`. OpenCV comes from the Maven Central AAR (`org.opencv:opencv`) via prefab; nothing is vendored. Keep the reproducibility flags in `app/src/main/cpp/CMakeLists.txt` (`-ffile-prefix-map`, `--build-id=none`) and the `ArtProfile` task disabling in `app/build.gradle.kts` (AGP's `baseline.prof` differs between machines), and never add path-, time- or machine-dependent build inputs (see Release workflow).
* **Assets:** `.tflite` and `.task` models must stay uncompressed (`androidResources.noCompress`).

## RELEASE WORKFLOW

When the owner asks for a new release / release build ("do a release", "cut 1.2.5", "new release build"), the agent runs **all** of these steps itself. That request is the approval to commit, tag, push `master` + the tag to GitHub, build, and publish the GitHub release. It is **not** approval to push to the fdroiddata fork (step 8 asks first).

The app ships on F-Droid with **reproducible builds**: F-Droid builds the tag from source and publishes it with the owner's signature only if its unsigned APK is byte-identical to the signed APKs on the GitHub release (a per-build `binary:` URL + `AllowedAPKSigningKeys` in the recipe). Every release therefore needs the signed per-ABI APKs from `scripts/build-release.sh` on GitHub.

1. **Version**: never add to an already released `CHANGELOG.md` section; every new change set is a new version. Bump the patch version unless told otherwise. Bump `baseVersionCode` (+1) and `versionName` in `app/build.gradle.kts`; per-ABI versionCodes are `baseVersionCode * 10 + n` (armeabi-v7a=1, arm64-v8a=2, x86=3, x86_64=4).
2. **Changelogs**: add `## [X.Y.Z] - YYYY-MM-DD` to `CHANGELOG.md` (Added / Improved / Fixed), and `fastlane/metadata/android/en-US/changelogs/<baseVersionCode>.txt` (short `•` bullets, plain text, under 500 characters).
3. **README.md**: update features if user-visible behavior changed.
4. **Manifest**: update `.agent_manifest.json` (`manifest_meta.app_version`, `last_updated`, and any affected entries).
5. **Commit and tag**: one commit `Release X.Y.Z: <summary>` (no AI attribution), then an annotated tag `git tag -a vX.Y.Z -m "Camera X.Y.Z"`. Push `master` and the tag (`git push origin master vX.Y.Z`). If the tag has been pushed, never move or re-create it: fix forward with a new version.
6. **Build the signed APKs**: `scripts/build-release.sh vX.Y.Z` (5–10 minutes, run it in the background). It reads the keystore and passwords from `~/.config/camera-release/release.env`. **Never read, print, cat or grep that file, and never ask for the password in chat.** If the file is missing, stop and ask the owner to create it (format at the top of the script). The script ends by printing the certificate SHA-256: it must equal `4529222b33ab8d3494d46a1b74cef8f6fdbdcbf6f4c3f09eda3d3ee2b50d558f` (`AllowedAPKSigningKeys`). If it differs, stop and tell the owner. Output: `dist/vX.Y.Z/Camera-X.Y.Z-<abi>.apk` for armeabi-v7a, arm64-v8a, x86 and x86_64. Never build release APKs from the working tree or Android Studio: only a fresh clean build of the tag reproduces, and the script's signing flags (v2+v3 only, `--alignment-preserved`) are required for F-Droid's signature copy.
7. **GitHub release**: `gh release create vX.Y.Z dist/vX.Y.Z/*.apk --title "Camera X.Y.Z" --notes "<the CHANGELOG section>"`. If `gh` is missing or not authenticated, tell the owner to create the release for the tag in the browser and upload the four APKs. The asset names must stay exactly as built: each recipe build entry has `binary: https://github.com/fearmikey/Camera/releases/download/v%v/Camera-%v-<abi>.apk` with its own ABI.
8. **F-Droid**: once the app is merged into fdroiddata, nothing to do: F-Droid's checkupdates picks up the new tag and verifies against the GitHub APKs. While the inclusion MR (https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58968) is still open, update `metadata/com.fearmikey.camera.yml` on the `com.fearmikey.camera` branch (worktree `~/fdroid/fdroiddata-camera`): the four build entries' `versionName`/`versionCode`/`commit` (full hash of the release commit) and `CurrentVersion`/`CurrentVersionCode`. Ask before pushing to the fork, and only push after the GitHub release has its APKs (the MR pipeline downloads them).
