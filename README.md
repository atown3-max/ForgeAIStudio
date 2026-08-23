# Forge AI Studio — Android v0.2

Native Android AI image/video studio for personal use.

## Providers

- **Replicate** — Qwen Image 2, Qwen Image Edit 2511, LTX‑2.3 Fast
- **RunPod Open** — Qwen Image and WAN 2.2 image-to-video using RunPod's documented optional safety-checker control

API keys are stored locally using Android Keystore and are never hard-coded into the APK.

## Open Studio

Open Studio is an adults-only provider mode. It can disable RunPod's optional model safety checker for broader lawful adult generation. Forge keeps hard boundaries against sexual content involving minors and non-consensual intimate imagery.

RunPod requires its own account, API key, and credits. The existing Replicate workflow remains available and unchanged.

## Build

The repository now contains the Android project as normal source files. GitHub Actions builds the debug APK directly from the repository root using Java 17, Android SDK 35, and Gradle 8.9.

The generated artifact is named `ForgeAIStudio-debug-apk` and contains `app-debug.apk`.

v0.2 feature-branch builds are validated before merge.
