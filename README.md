# Forge AI Studio — Android v0.5.1 RunPod Recast preview

Native Android AI image, multi-reference editing, character-consistency, prompt-optimization, and image-to-video studio.

## Recast preview

**Video → Recast** accepts an existing MP4/MOV and an uploaded character image or one selected from the Character Bank. Choose a 2/5/10-second section and whether to keep original audio. This preview supports one visible person and uses **Wan2.2-Animate-14B replacement mode** through either RunPod Serverless or the included Forge GPU worker.

### RunPod Hub endpoint

1. Deploy the [Wan Animate template](https://github.com/wlsdml1114/Wan_Animate_Runpod_hub) as a queue-based Serverless endpoint. This adapter targets the `replace` API reviewed at commit `46791944f96ed4c74be4d5f109935e994d2d394d`; the template is deployed independently and is not bundled in Forge. Template dependencies, licensing and GPU compatibility need separate review.
2. For the initial test use one 32GB PRO GPU, active workers **0**, maximum workers **1**, idle timeout **5 seconds**, execution timeout **1,800 seconds**, and FlashBoot enabled. These are conservative test settings, not a hardware compatibility guarantee.
3. In **More → Settings → Recast**, choose **RunPod Serverless**. Paste the endpoint ID or `https://api.runpod.ai/v2/YOUR_ENDPOINT_ID`. `/run` and `/runsync` URL suffixes are accepted and normalized. Do not paste the entire cURL command.
4. Enter a RunPod API key with access to that endpoint in **RunPod API key for Recast**. It is encrypted separately from the key used by Forge's other RunPod features. Never share the key in screenshots; rotate any exposed key.
5. Tap **Test Recast connection**. This only calls `/health` to verify access. Zero warm workers is valid; this test does not start a paid render or prove that the model works.
6. In **Video → Recast**, start with one 2-second clip, a clear full-body reference, and permission acknowledgement. Then test 5/10 seconds. Forge prepares the selected time range as H.264/AAC at up to 832×480 (portrait dimensions swap), preserves aspect ratio with minimal padding, and removes audio before upload when requested. The Hub request uses `mode: replace`, 16 FPS, 4 steps, CFG 1 and raw Base64 inputs. Uploads exceeding the 10MB RunPod JSON limit are rejected before submission; no S3 account or network volume is needed for this path.
7. Keep Forge open until the job ID is saved. `/run` submits asynchronously; polling uses `/status/{id}`. Return before **30 minutes after completion**, when RunPod deletes results. Once downloaded, the video is saved to History and can be shared or saved to the gallery.

Known accepted job IDs are reused after reopening; reconnect never calls `/retry` or starts another render. If an upload was interrupted before its job ID was saved, Forge blocks automatic resubmission because RunPod may already be rendering it. Recover with the existing job ID from the endpoint's **Requests** tab. A 404 for an accepted job never causes a replacement job. Failed jobs still consume GPU time. Job recovery is local to this phone; the app does not run a background download service.

### Forge worker

Alternatively deploy the included [Recast worker](recast-worker/README.md), choose **Forge worker** in Settings, and enter its HTTPS origin and dedicated worker token. This path supports 480p/720p area targets at 30 FPS. Ordinary RunPod/Replicate keys do not provision it. It uses client-generated IDs and a durable queue; results expire after 24 hours while the worker runs.

Recast remains a preview: unit tests and APK builds do not prove phone-side video encoding, GPU compatibility, render quality, runtime or cost. Validate a real clip on the selected device and endpoint before normal use. No live render is triggered by installing this APK or testing the connection.

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

Every image-to-video request automatically begins with:

`make this image come alive, cinematic motion, smooth animation`

The user's motion instructions and selected continuity context are appended after that prefix.

Recast uses the source video's motion and its reference image directly. It has its own replacement pipeline and does not use this prompt prefix.

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

`ForgeRecast-preview-apk` is also built with `-PrecastPreview=true`: it installs as **Forge Recast Preview** alongside the original app, with separate settings, History and Character Bank. This avoids uninstalling an existing Forge installation when debug signing certificates differ between build environments. Use this separate preview for the first live endpoint test; select your character image from the phone and enter the replacement key there. No existing app data is migrated or deleted.
