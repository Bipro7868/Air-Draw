# AirDraw 3D (starter prototype)

Android camera + MediaPipe hand landmarks + pinch-to-draw overlay. This is a **starter prototype**, not a finished spatial 3D drawing system.

## Requirements
- Android 8.0+ (minSdk 26; tested target is Android 8.1 class device)
- Camera permission
- Gradle/JDK 17 in cloud build
- The MediaPipe model file downloaded into `app/src/main/assets/hand_landmarker.task`

## Build in Codemagic
1. Push this project to a GitHub repository.
2. Add the repository in Codemagic.
3. Select the `codemagic.yaml` workflow.
4. Start a build and download the debug APK from artifacts.
5. Install it on your phone and allow camera permission.

The workflow downloads the model before building.

## Current prototype behavior
- Opens the rear camera.
- Runs MediaPipe Hand Landmarker with up to two hands.
- Detects thumb-index pinch and draws a cyan 2D line following the index fingertip.
- Clear button removes the drawing.

## Not implemented yet
- Separate strokes from each hand / deliberate two-hand shape gestures
- True 3D coordinate reconstruction, calibrated depth, 3D object manipulation
- Front/rear camera selector, color/brush controls, saving drawings

Depth from a single RGB camera is approximate. For better results, calibrate the mapping and use a dedicated depth sensor or multi-view approach.

## Important
The Android build may require dependency/version adjustments if a library changes. MediaPipe's official Android example and setup guide are useful references:
- https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker/android
- https://github.com/google-ai-edge/mediapipe-samples
