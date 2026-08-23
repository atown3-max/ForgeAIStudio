# Forge AI Studio — Android v0.3

Native Android AI image, image-editing, and image-to-video studio for personal use.

## Workflows

The app is organized around five clear tabs:

- **Image** — text-to-image with Qwen Image 2 on Replicate
- **Edit** — image-to-image with RunPod Qwen Image Edit, plus Replicate Qwen Image Edit 2511 as a fallback
- **Video** — image-to-video with RunPod WAN 2.2 or Replicate LTX‑2.3 Fast
- **History** — locally cached completed generations with quick Edit and Animate actions
- **Settings** — encrypted Replicate and RunPod credentials

## Image-to-image

Edit accepts an image from the Android gallery or a previously generated Forge image. Local images are compacted to a JPEG data URL before submission. This follows the local-file pattern used by RunPod's official AI SDK.

RunPod Edit can use the provider's documented optional safety-checker setting for broader lawful adult generation. Forge keeps hard boundaries against sexual content involving minors and non-consensual intimate imagery.

## Image-to-video

Video requires a first-frame image. WAN 2.2 is the default RunPod route; LTX‑2.3 Fast remains available as the Replicate route and supports an optional last frame plus its existing resolution, FPS, camera, and audio controls.

Every video request automatically begins with:

`make this image come alive, cinematic motion, smooth animation`

The user's motion description is appended after that prefix.

RunPod WAN jobs are submitted asynchronously and polled until completion. Forge accepts common RunPod output shapes such as `video_url`, `result`, and `url`, and includes job/output diagnostics when a provider response is malformed.

## Security

Replicate and RunPod API keys are stored locally using Android Keystore and are never hard-coded into the APK.

## Build

The Android project is checked into the repository as normal source files. GitHub Actions builds the debug APK from the repository root using Java 17, Android SDK 35, and Gradle 8.9.

The generated artifact is named `ForgeAIStudio-debug-apk` and contains `app-debug.apk`.
