# Changelog

All notable changes to this project will be documented in this file.

## [1.2.5] - 2026-11-25

### Fixed
- F-Droid could not verify 1.2.4 against the signed GitHub APKs: the ART startup profile that the build packs into the APK (`baseline.prof`) comes out slightly different on each build machine. Release APKs no longer include it, so F-Droid's build matches and ships with the developer's signature. The first launch after installing may be marginally slower.

## [1.2.4] - 2026-11-25

### Improved
- Release builds are reproducible. The native image-processing library no longer embeds the paths it was built from, so F-Droid can verify its build against the signed APKs on the GitHub release and ship them with the developer's signature. F-Droid and GitHub installs can be updated from either source without reinstalling.

## [1.2.3] - 2026-11-24

### Improved
- Night photos are much brighter and have real contrast, closer to what a phone's night mode produces. The merge used to brighten by at most 2.6 EV toward a dim target with a curve that flattened everything, so dark scenes stayed dark and grey. It now measures the merged frames and brightens by up to 5 EV to just under middle grey, held back when a lit part of the scene would wash out (a lit building under a black sky sets the exposure, not the sky), and applies a contrast curve that lets the deepest shadows fall to black. A scene that is already bright comes out unchanged.
- Colored lights keep their color in Night photos: warm windows and neon signs roll off toward their own color instead of fading to white.
- Very dark scenes use up to 20 frames (previously 14) to hold down the noise that the stronger brightening reveals.

### Fixed
- Shadows in Night photos could carry a grey or purple haze: the sensor's black level is now measured per color channel and removed before brightening.

## [1.2.2] - 2026-11-24

### Improved
- Scanned QR codes and barcodes now appear as a large white chip just above the zoom buttons instead of under the top bar, so the link or action is within thumb reach on big phones.

### Fixed
- Shorter freeze after stopping a Slow motion or Time lapse recording (and after every mode switch): the list of slow-motion speeds is now looked up once per camera, off the main thread, instead of re-reading every camera's details each time the camera restarted.
- Back no longer closes the app from a video mode: it returns to the photo mode you came from (and from Night, Portrait, Long exposure or Panorama to Photo). In Photo, Back still leaves the app.

## [1.2.1] - 2026-11-22

### Added
- Settings → About: links to the source code on GitHub, the changelog and the issue tracker, the GPL-3.0 license notice and the open-source library licenses, plus a "Buy me a coffee" link to support development. Links open in your browser; the app itself still has no network access.
- Slow motion: live viewfinder while recording (Time lapse too) instead of a dark "Recording..." screen. After you stop, the last frame stays up until the normal viewfinder is back.
- Slow motion speed choice: 1/4x (120 fps) or 1/8x (240 fps), showing only the rates your camera supports; the choice is remembered. 1/8x records at up to 720p on most phones. The speed button at the bottom right shows the current speed and opens the Speed control, next to Exposure, in the adjustments row.
- Exposure adjustment in Slow motion and Time lapse: the adjustments row now opens there too (Exposure, plus Speed in Slow motion).

### Improved
- Photo modes are now ordered Long exposure · Portrait · Photo · Night · Panorama, so Night sits right next to Photo.
- Video modes are now ordered Slow motion · Video · Time lapse, so Video sits in the center like Photo does in its row.
- Slow motion picks the largest 16:9 size for the chosen speed and scales the bit rate to it, and switching to Slow motion on a camera without high-speed video says so right away instead of only when you press record.
- The recording timer starts when frames actually reach the file, so in Slow motion and Time lapse (which first open the camera) it no longer runs ahead of the clip.
- Redesigned settings screen: collapsing large title, settings grouped into rounded cards with an icon for each setting, check-mark switches, an app info card (icon, version) and more accessible switches and choice dialogs (the whole row toggles; radio options are announced as a group).
- Portrait no longer cuts off dark shoulders: a black shirt against a dark wall keeps its natural rounded shoulder outline instead of being blurred along a straight vertical line.
- Refreshed camera screen: status icons (12MP/MAX badge, FHD/4K badge in Video, flash, timer, macro) line up on the left of the top bar, the shutter has a dark ring (Slow motion and Time lapse show their icon on a white disc), the gallery and flip buttons are rounded squares, and the selected mode, zoom preset and photo/video toggle use the Material You accent. Tapping the FHD/4K badge switches video resolution.
- Redesigned in-camera settings sheet: always dark and see-through, large title with a "…" button for all settings, the current value of each setting in large accent text, and every option (including on/off settings that used to be switches) as an icon button; the selected one turns into an accent circle. Slow motion and Time lapse show their few settings on one page.
- Redesigned manual controls: a row of tools (Reset All, Exposure, ISO, Shutter speed, Focus, White balance) replaces the mode row, the selected tool opens a sliding tick dial above the shutter with its value shown above it, and a strip at the top shows which controls are automatic or manual. Exposure compensation is now part of manual controls too.

### Fixed
- Slow motion / Time lapse: stopping right after starting (while the camera was still opening) could leave the recording running in the background and fight the viewfinder for the camera; it now cancels cleanly with "Video too short".
- Stopping a Slow motion or Time lapse recording no longer freezes the UI while the video file is finalized, and the normal viewfinder only comes back once the recording has released the camera.
- Palm timer: a natural open palm held up beside your head now works; you no longer need to spread your fingers wide. Other people's faces in the shot are ignored.

## [1.2.0] - 2026-11-22

### Added
- Code scanner: EAN-13/EAN-8/UPC-A/UPC-E barcodes and several codes in one frame (OpenCV `QRCodeDetectorAruco` + `barcode::BarcodeDetector`, still on-device).
- Live outline of detected codes on the viewfinder (mapped with CameraX `CoordinateTransform`), with a short haptic tick for each new code.
- Result sheet with smart actions: join Wi-Fi through the system "Add network" dialog (no Wi-Fi permissions), add contacts (vCard/MECARD), add calendar events (iCalendar), write email/SMS, dial, open maps, web-search product numbers, plus Copy and Share.
- Scan a code from an image (top-bar button in Photo mode, and from the result sheet) via the system Photo Picker; tries an inverted pass for light-on-dark codes.
- Unit tests for the payload parser (`CodePayloadTest`).
- Portrait: adjustable background blur (ƒ/1.4–ƒ/16 aperture pill above the zoom buttons; remembered).
- Portrait: live face brackets on the viewfinder showing who stays sharp, plus "No face found" / "Move closer" hints.
- Portrait: "Keep original portraits" setting (Settings → Photo) also saves the unblurred photo.
- Portrait zooms like a portrait lens (as in Google Camera): the rear camera starts at 2x with 1x/2x buttons and pinch from 1x to 3x; the front camera starts at its full view with .7x/1x buttons and pinch up to 2.9x.
- Wide selfie cameras (e.g. Pixel) label their full view ".7x" and offer a "1x" crop, matching Google Camera.
- Top-down leveler: after the camera points straight down for a second, a center circle and a bubble dot appear; level the phone to bring the dot into the circle (it turns the accent color).
- Flash setting "Auto Night" (like Pixel's Auto Night Sight): no flash, and Photo-mode shots in a dark scene automatically use the Night pipeline. A moon appears on the shutter (and the top-bar icon turns the accent color) when the next shot will be a night shot. The flash options are now Auto Night / Auto flash / On / Off; a previously saved "Auto" becomes Auto flash.

### Improved
- Smoother UI: pinch zoom, zoom-preset glides and Panorama no longer redraw the whole camera screen every frame, pinch zoom no longer drops steps when several touch events arrive in one frame, and turning the phone or fading controls animates without recomposing the controls.
- Links that hide their real host (`user@host`) or aren't https show a warning before opening.
- QR text that isn't valid UTF-8 falls back to ISO-8859-1 instead of being passed to JNI as-is.
- Wi-Fi passwords are copied as sensitive clipboard content; the copy toast only shows below Android 13.
- Portrait rendering rebuilt (`PortraitEngine.cpp`): lens-style disc bokeh in linear light with glowing highlights instead of a Gaussian blur, guided-filter matte refinement for cleaner hair and edges, grain matched to the sharp subject, head-and-shoulders segmentation prior, small holes in the subject filled.
- Portrait handles group shots: every prominent face is kept sharp (not just the largest).
- Portrait blur is even around the head and shoulders: background next to the subject (chair backs, shelves behind dark hair or a dark shirt) no longer stays sharp in patches, hair and ears stay sharp, and the soft edge has a consistent width.
- Portrait uses the camera's face-priority focus/exposure/white balance when available.
- The horizon leveler only appears when the phone is held in landscape (like Pixel's framing hints), with fixed side marks and a line that follows the horizon.
- Tap-to-focus holds on the subject until the phone is turned away (about 10°), instead of drifting back to continuous autofocus after 5 s. It also uses a smaller, more precise focus region.
- Mode selector: swiping the mode row snaps the nearest mode to the center and switches to it (with a haptic tick as each mode passes the center); the selected mode always sits centered, including the first and last modes.
- Zoom buttons follow the phone's actual lenses (e.g. .5 / 1x / 2x / 5x on a Pixel Pro). There are no digital-only stops like 10x; pinch still zooms all the way. Sensor-crop variants of the same lens, which Pixels list as extra cameras, no longer add a bogus 10x.
- Zoom buttons (and Portrait's aperture pill) float inside the bottom of the viewfinder instead of straddling its edge.
- UI orientation changes now use hysteresis, and the orientation no longer flips while the phone lies flat.
- Faster, smoother mode switching: Video, Slow motion and Time lapse switch without reconfiguring the camera at all, and Photo ↔ Video keeps the camera open and the viewfinder streaming (only the capture session is reconfigured). Video stabilization and the frame-rate pin are now applied live, so changing them no longer restarts the camera.
- Transitions: the viewfinder cross-fades from a blurred last frame instead of freezing or flashing black while the camera restarts (camera flip, Portrait/Panorama, after Night/HDR); the mode row cross-fades between photo and video modes; the selected mode pill and the photo/video toggle highlight animate.
- Redesigned shutter: a white ring around a white (photo) or red (video) disc that springs into a small stop square while recording, shrinks when pressed and bounces back, sends a ring pulse on each photo, and turns into a progress ring during Night/HDR/Long exposure captures. Haptic feedback on tap and hold. The video-snapshot button is now a miniature photo shutter, and the side buttons cross-fade when recording starts or stops.
- Smooth zoom presets: tapping .5x / 1x / 2x / 5x glides the zoom over ~0.2–0.4 s (evenly paced per doubling, so lens switches happen mid-move instead of as a cut) and the white highlight slides to the tapped preset while its label counts to the new value. Pinch stays immediate and interrupts a glide. Volume-key zoom glides too. Light haptic tick on preset taps.
- The recording time moved to a pill at the top of the viewfinder with a blinking red dot (pause icon while paused).
- Night mode rebuilt: AE meters with the slowest frame rate, frames use up to 4x the metered exposure time (max 1/8 s, less when zoomed in), darker scenes get more frames (8–14), white balance is locked across the burst, and the merge is brightened in linear light (auto gain from the scene's log-average luminance, black point, highlight roll-off instead of clipping, 12-bit averaging against banding) with chroma noise removal.
- Night photos are sharper and free of banding: frames are aligned to a fraction of a pixel (previously to the nearest ~4 px, which smeared detail), captured with light noise reduction and OIS, and limited to 1/15 s on phones without OIS. The merge is processed at 16 bits, sharpened once and dithered, replacing the 8-bit local-contrast step that drew contour lines across smooth dark walls.
- The shutter's progress ring (and the "Hold still" spinner) now fill as a Night, Auto Night, Long exposure or HDR shot meters, collects its frames, merges and saves. Previously the ring stayed empty, and Photo-mode HDR showed no ring at all.
- Long exposure rebuilt for higher quality:
  - Each frame is as long as hand-holding allows (up to 1/8 s with OIS) at the camera's lowest ISO, and frames run back to back. Motion now smears smoothly instead of leaving stepped copies, and noise is lower. Brightness still follows the metering and the brightness slider.
  - 16–32 frames covering about 2 seconds, instead of a fixed 16.
  - Frames are averaged in linear light, like one continuous exposure. Light trails and white water are no longer dimmed and muddied, and the result is kept at 16 bits and dithered, so skies don't band.
  - Light trails: headlights and street lights moving through a dark scene come out as bright, saturated trails instead of faint gray streaks.
  - Static parts of the scene are sharper. Frames are aligned against up to three detailed areas instead of one, so moving water or traffic can no longer pull the alignment off, and the merge is sharpened once (frames are captured without in-camera sharpening).
  - Frames use light noise reduction, which keeps texture and avoids slowing the frame rate.
- Night and Long exposure tolerate a lost frame: the shot is merged from the remaining frames instead of failing or timing out. If a capture does time out, its memory is freed.
- The low-light detector accounts for the camera's post-RAW digital boost and needs two readings in a row to change, so it no longer flickers after the camera restarts.
- Controls only appear where they work. One shared rule (`ModeCapabilities`) now decides both which pipeline a shot uses and which controls each mode shows:
  - Flash offers only what the mode supports: Portrait has no Auto Night, video modes show torch on/off, and RAW + JPEG, Night, Long exposure and Panorama have no flash button.
  - The 12MP/MAX chip hides when HDR or RAW + JPEG decides the resolution.
  - The timer is not used in Panorama, and the MACRO chip only shows in Photo and Portrait.
  - Holding the shutter acts as a tap where there's no QuickTake.
  - Slow motion and Time lapse show no snapshot button or zoom row while recording.
  - The quick settings sheet only lists rows that apply to the current mode, and explains when HDR isn't used (flash on, RAW + JPEG).
- Manual controls (Tune) are dimmed in modes that ignore them (Night, Long exposure, Panorama, Slow motion, Time lapse, Photo with HDR). The panel closes when you switch to such a mode, and Auto Night stays off while manual ISO or shutter is set.
- The torch now stays on while recording Slow motion and Time lapse.
- While recording, the mode row, photo/video toggle, settings button and top-bar chips are locked and dimmed (changing them used to restart the camera and end the recording).
- Switching mode or camera now starts fresh: brightness returns to 0, the tap-focus lock is released and the manual controls panel closes (its values are kept for next time). These used to carry over invisibly.
- Panorama always starts at 1x instead of keeping the zoom from the previous mode.
- On the front camera, Photo now says when HDR or RAW + JPEG is on but can't be used there, instead of quietly taking a normal photo.
- Settings cleanup: the quick settings sheet and the full settings screen use the same names (HDR, Grid, Framing hints, Stabilization, 12MP), the grid is chosen with buttons like every other row, and photo resolution and file format can now also be set in the full settings.
- Macro focus is simply Off or On. The old default "Auto" behaved exactly like Off and now loads as Off.
- Removed settings that were stored but never did anything (Top Shot, Motion Photo, lens selection, Ultra HDR, HEIF, video codec and a few leftovers). Changing the photo resolution no longer restarts the camera.

### Fixed
- Night photos came out darker than the viewfinder: the manual exposure dropped the post-RAW sensitivity boost that auto-exposure adds in the dark (e.g. on Pixels), and the old tone map barely brightened. The boost is now carried over and the result is brightened. The HDR bracket's 0 EV frame had the same boost problem.
- The shutter no longer jumps up when recording starts (the recording timer used to sit underneath it and push it up).
- A shutter touch that turns into a swipe no longer counts as a long press and starts QuickTake.
- Zoom no longer silently resets to 1x on the camera (while the zoom buttons still showed the old value) after a mode switch.
- Switching between Photo and Video now clears stale code and low-light suggestions, and is ignored during a panorama sweep.
- Portrait no longer processes full-sensor (e.g. 50 MP) bitmaps: input is capped at ~12.6 MP, avoiding very slow captures and out-of-memory failures.
- Tap-to-focus focused and metered on the top-left corner of the frame instead of the tapped spot: normalized coordinates were passed to `PreviewView`'s pixel-based metering factory.
- Opening the camera with the power-button double-press while the phone is unlocked no longer starts the restricted lock-screen camera (no gallery or image scan, closing as soon as you leave). Only the secure lock-screen launch is restricted now.
- Pressing the shutter or volume key while a Night, HDR or Long exposure shot was still processing started a second capture.
- With a timer set, tapping the shutter to stop a panorama sweep started a countdown instead of stopping.
- Double-tap camera flip, pinch zoom and volume-key zoom could disrupt a panorama sweep.
- Turning off manual controls didn't apply to RAW + JPEG shots, which kept the old manual ISO/shutter/focus. Setting only ISO or only shutter now fills in the other value the same way in the preview and the photo.
- Night, HDR and RAW shots taken right after switching cameras could be cropped, using the previous camera's zoom.
- The flash never fired with HDR on, because HDR shots can't use it. With Auto flash or On, Photo now takes a single flash shot and HDR applies when the flash is Off or Auto Night.
- HDR on phones without manual sensor control ignored the brightness slider.
- A failed Slow motion or Time lapse recording (e.g. stopped too quickly) gave no message and could leave QuickTake stuck in video mode.
- Slow motion on the front camera could silently record from the rear camera; it now says it isn't available on the front camera.
- The screen flash no longer blinks when a tap starts or cancels the timer, starts a Slow motion or Time lapse recording, or is ignored while a shot is processing.
- Night, Long exposure, HDR and RAW + JPEG photos ignored the aspect ratio and always came out 4:3. They now match the viewfinder: 16:9 uses a 16:9 sensor size and Full is cropped to the screen's shape. The DNG still keeps the whole sensor.
- With the Full ratio, photos showed much more than the viewfinder (the whole 4:3 frame). They are now cropped to what the screen showed.
- Tap-to-focus was ignored by Night, Long exposure, HDR and RAW + JPEG shots, which refocused on the center. They now focus and meter on the tapped spot until the focus lock is released.
- "Keep selfies as seen" was ignored by Night and Long exposure on the front camera and by front-camera videos.
- The palm-timer face and hand boxes could stay on screen after switching modes or turning the palm timer off.
- The palm timer rarely started: lowering your hand (or a missed frame) during the 1-second lock-on canceled the countdown. Once your palm is recognized, the timer now always starts.
- Palm timer: works with the phone held sideways, finds people a few meters away (higher-resolution face search, hand search at full resolution around the face), and recognizes a raised hand when a bare forearm or shoulder is in view.
- US English spelling throughout the app and docs (e.g. "blur centers on the subject").
- Lock-screen camera: the thumbnail showed your newest gallery item (which could be a private photo) before you took anything. It now only shows photos from that session, and opening the photo or "All settings" asks you to unlock first.
- Hold-to-record felt laggy: a hold now starts after 0.3 s instead of 0.5 s, and in Photo/Portrait the shutter switches to its recording state right away instead of waiting for the camera to switch to video.
- The start-recording chime was picked up by the microphone at the beginning of videos. The audio stays muted while the chime plays, and the stop chime now plays after the recording has stopped.

## [1.1.0] - 2026-11-18

### Added
- System theming: Material You dynamic colors from the wallpaper and automatic light/dark (`ui/theme/Theme.kt`). Settings and the quick-settings sheet follow the system theme; the viewfinder stays dark but uses the system accent.
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
- Mode rows now: Night · Portrait · Photo · Long exposure · Panorama, and Slow motion · Time lapse · Video (HDR removed from the row). Mode lists/labels centralized in `CameraModes`.
- Settings reorganized into General / Composition / Fast access / Photo / Video / Advanced / About; quick-settings timer is Off/3/10 s.
- Volume-key auto-repeat no longer fires bursts of shots.
- Layout: viewfinder anchored under the top bar so the shutter row never overlaps it; tighter bottom controls; centered mode carousel; live zoom value in the zoom pill; control icons rotate with the device; duplicate top flip button removed.
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
