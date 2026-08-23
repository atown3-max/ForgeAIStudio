# Forge AI Studio — Android v0.4

Native Android AI image, multi-reference editing, character-consistency, prompt-optimization, and image-to-video studio.

## Main tabs

- **Image** — Qwen Image 2 text-to-image with Prompt AI, 1/2/4 output variants, and reusable seeds.
- **Edit** — multi-reference image editing with Qwen Image Edit 2511, reference roles, saved Character/Background Banks, plus the existing single-image RunPod Open route.
- **Video** — WAN 2.5 Fast, WAN 2.6 Advanced, Kling O1 multi-reference character video, and Replicate LTX 2.3 Fast.
- **Bank** — local Character Bank and Background Bank with reusable reference views, continuity notes, locked traits, and seed banks.
- **More** — Prompt Lab, generation-recipe History, and Settings.

## Multi-reference editing

Qwen Image Edit 2511 accepts up to three images per edit. Forge treats the first image as the base and lets additional images be assigned roles such as subject/identity, face, hair, clothing, pose, style, background, or detail. Saved Character and Background Bank references can fill available model reference slots automatically.

Image generation and supported edit routes can generate 1, 2, or 4 controlled variants. Forge stores the actual seed and generation recipe with every local result.

## Character and Background Banks

Character profiles can store multi-angle identity references including front, 3/4, profiles, rear, face close-up, full body, expressions, and outfits. They can also store portrait, full-body, and video seeds plus traits the prompt builder should preserve.

An optional **adult-only anatomy reference** role exists solely for structural/proportion continuity. These files are stored in the app's local profile library and are excluded from automatic use unless explicitly enabled for that generation. Never use this feature for minors or non-consensual intimate material.

Background profiles store recurring environment views, continuity notes, and separate image/video seeds.

## Prompt AI

Forge includes a Prompt Lab and inline Prompt AI buttons powered through the user's RunPod account. The optimizer can rewrite rough prompts for text-to-image, editing, image-to-video, character consistency, or background consistency. Actions include Optimize, Cinematic, Preserve identity, Merge references, More realistic, and Simplify. Optimized text remains editable before generation.

## Video

Every video request automatically begins with:

`make this image come alive, cinematic motion, smooth animation`

The user's motion instructions and selected continuity context are appended after that prefix.

Video routes include:

- **WAN 2.5 Fast** — the proven quick path, 5 or 10 seconds at 720p.
- **WAN 2.6 Advanced** — 5/10/15 seconds, 720p/1080p, single- or multi-shot controls.
- **Kling O1 Character References** — uses up to ten reference images for multi-view character/prop/scene consistency.
- **LTX 2.3 Fast** — retains its duration, resolution, FPS, camera-motion, last-frame, and audio controls.

Completed video results include a **Continue shot** action that extracts the final frame locally and loads it as the next clip's starting frame.

## Seeds and recipes

Seed controls support random generation, a locked seed, a newly generated locked seed, or reuse of the most recent image/video seed. Seeds are treated as part of a generation recipe rather than as the sole identity anchor; reference images remain the stronger continuity source.

History stores the final prompt, original rough prompt when Prompt AI was used, seed, model/settings, reference summary, and local output file.

## Security and storage

Replicate and RunPod API keys are stored locally using Android Keystore and are never hard-coded into the APK. Character/Background Bank images are copied into the app's internal storage for reuse.

## Build

The project targets Android SDK 35 and Java/Kotlin 17. GitHub Actions validates pull requests and builds a debug APK using Gradle 8.9. The build artifact is named `ForgeAIStudio-debug-apk` and contains `app-debug.apk`.
