# Sotto

**Your networking wingman, in a whisper.**

An Android proof of concept built to *Conference Networking AI Assistant — Design Doc 1:
Proof of Concept Specifications (Phone-Only Version)*.

Sotto uses only the phone's camera and microphone to recognise conference attendees,
follow the conversation, and — at a natural pause — whisper one useful thing into your
earpiece.

> **Installing it?** See [INSTALL.md](INSTALL.md). This file is about how it's built.

---

## The four pipelines

Design Doc 1 § Core Architecture specifies four parallel pipelines. Each maps to one
place in the code.

| Pipeline | Implementation |
|---|---|
| Face detection and matching | `vision/FacePipeline.kt` |
| Speech transcription | `data/remote/SpeechToTextClient.kt`, driven by `audio/VoiceActivityDetector.kt` |
| Conversational AI decision-making | `data/remote/GeminiLiveClient.kt` + `domain/PromptBuilder.kt` + `domain/PauseDetector.kt` |
| Audio output | `data/remote/TextToSpeechClient.kt` → `audio/WhisperPlayer.kt` → `audio/BluetoothAudioManager.kt` |

`domain/SessionOrchestrator.kt` conducts all four and owns every fallback in
§ Error Handling.

## Stack

Exactly as § Technical Stack specifies, with five deviations noted below.

- **Min SDK 30, target SDK 35**, Kotlin 2.0, Jetpack Compose, Material 3
- **Face detection** — MediaPipe Face Detection 0.10.26.1 (BlazeFace short-range)
- **Face embedding** — FaceNet TFLite via LiteRT, 160×160 input → 128-d output
- **Speech-to-Text** — Gemini Live input transcription (Cloud STT optional)
- **Conversation** — Gemini Live API
- **Text-to-Speech** — Android `TextToSpeech` (Cloud TTS optional)
- **Audio out** — `AudioTrack`, routed to Bluetooth
- **Storage** — local JSON file
- **Networking** — OkHttp (REST + WebSocket)

### Deviations from the design doc, and why

1. **Gemini Live over WebSocket, not gRPC.** The doc says gRPC. The public Live surface
   exposes the same `BidiGenerateContent` service over a WebSocket, and that is the
   transport an API key can authenticate. gRPC here needs service-account credentials —
   a genuinely bad thing to ask someone to load onto their phone. Same service, same
   message shapes, key-friendly transport.

2. **Speech and voice do not use the Cloud APIs the doc names.** This is the deviation
   that cost the most to discover. Cloud Speech-to-Text and Cloud Text-to-Speech
   *reject API keys outright* — they accept only OAuth2 / service-account credentials.
   No amount of enabling APIs or minting new keys can change that; a user proved it
   with two separate keys on a correctly-configured project.

   So the defaults moved to the two paths a key-holder can actually use:

   - **Transcript** — Gemini Live's own `inputTranscription`, over the same
     already-authenticated socket. Nothing extra to enable.
   - **Voice** — Android's built-in `TextToSpeech` engine (`audio/DeviceTtsEngine.kt`).
     No key, no network, no account, works in flight mode.

   Both cloud clients remain, selectable in Settings for anyone holding real
   credentials, and `SessionOrchestrator.speak` falls through from cloud to device
   automatically so a rejection can never leave the user in silence. When Cloud STT
   *is* enabled it runs utterance-at-a-time rather than as a continuous stream, since
   Cloud's true streaming endpoint is gRPC-only; the VAD segments the feed and posts
   each utterance at the pause, which lands inside the doc's 500–1500 ms window.

3. **The FaceNet model ships as float32, not int8.** The doc asks for int8 quantisation.
   Re-quantising needs the original SavedModel, which isn't published — only the
   converted `.tflite`. It runs on XNNPACK and comfortably meets the doc's 80–120 ms
   detect-plus-embed budget; the cost is 23 MB of APK.

4. **R8's optimiser is off (`-dontoptimize`), shrinking is on.** MediaPipe logs through
   Flogger, which resolves its logger by walking the call stack. The optimiser rewrites
   those frames, so `FluentLogger.forEnclosingClass()` throws at class-init time and
   face detection fails on a release build while working perfectly in debug. Shrinking
   stays on because without it the APK doubles to 99 MB. `app/build.gradle.kts` also
   fails the release build if any 64-bit `.so` is not 16 KB page-aligned, and
   `vision/TfLiteFaceDetector.kt` is a pure-LiteRT BlazeFace fallback for the case where
   MediaPipe cannot initialise at all.

5. **Face enrolment exists as a user-facing flow.** The doc treats embeddings as
   precomputed inputs. In reality a roster contains names and titles, never face
   vectors. `ui/roster/EnrollScreen.kt` captures three samples and averages them into
   the 128-float embedding the schema calls for, using the same on-device model that
   runs live. Without this the documented recognition flow cannot be exercised at all.

## Layout

```
app/src/main/java/ai/sotto/assistant/
  core/          errors, logging, dispatchers
  data/
    model/       Attendee, ConferenceDatabase, session types
    local/       JSON store, encrypted key store, settings
    remote/      Gemini REST + Live, optional Cloud STT/TTS
  vision/        detection, embedding, matching, tracking, imaging
  audio/         capture, VAD, playback, Bluetooth, sound cues
  domain/        orchestrator, prompts, pause detection, enrichment
  ui/            Compose screens and view models
  di/            manual dependency container
  service/       foreground session service
```

No annotation processors. Dependencies are wired by hand in `di/AppContainer.kt`, which
keeps builds fast and makes every collaborator trivially swappable in tests.

## Tests

```bash
./gradlew test                      # 418 JVM tests
./gradlew connectedAndroidTest      # instrumented, needs a device
./gradlew lintRelease               # zero errors
```

The JVM suite covers what the doc's § Testing Strategy asks for and rather more:

- cosine similarity and matching, including the threshold, ties, and degenerate vectors
- tracking, dwell timing, and re-acquisition
- VAD onset/hangover against synthesised speech and room noise
- pause-detection cadence over simulated conversations
- Gemini Live setup-frame construction and response parsing
- enrichment JSON parsing, including fenced and chatter-wrapped output
- repository durability, corruption quarantine, atomic writes
- every fallback in § Error Handling

The instrumented suite loads the real `.tflite` assets and asserts the model's actual
output shape, determinism and latency.

## Why face detection has two implementations

MediaPipe's Java layer proved to be the least reliable thing in the app. On a real
device (OnePlus, Android 16, arm64) `com.google.mediapipe.framework.Graph` failed its
static initialiser with:

```
IllegalStateException: no caller found on the stack for
  com.google.common.flogger.FluentLogger
```

MediaPipe initialises a logger via Flogger's `FluentLogger.forEnclosingClass()`, which
identifies its caller by walking the call stack. R8's **optimiser** inlines and merges
frames, the walk finds no caller, and every MediaPipe task dies with
`NoClassDefFoundError` — while the same TFLite runtime happily ran FaceNet in the same
process. Keep rules do not help, because nothing is being removed; the stack is simply
a different shape.

Two changes came out of that:

1. `-dontoptimize`. Shrinking still runs (it is what keeps the APK at 53 MB instead of
   99 MB); only the optimiser is off, and it is the optimiser that rewrites call stacks.
2. `vision/TfLiteFaceDetector.kt` — the same BlazeFace model decoded directly on LiteRT,
   with anchors and NMS implemented in `BlazeFaceAnchors` / `BlazeFaceDecoder`. It is
   selected automatically whenever MediaPipe won't start, so a single fragile dependency
   can no longer take face detection down. The decoding maths has 30 unit tests, because
   a mistake there produces confident boxes in the wrong place rather than an obvious
   failure.

`Diagnostics` in the app reports which detector is actually in use.

## 16 KB page alignment

Android 15 introduced 16 KB memory pages, and they are the default on Android 16 and new
2025+ hardware. A native library whose ELF LOAD segments are 4 KB aligned cannot be
`dlopen`ed on such a device at all.

Sotto shipped 1.0.0 with exactly that bug: `mediapipe:tasks-vision:0.10.14` and
`org.tensorflow:tensorflow-lite:2.16.1` both emit 4 KB-aligned `.so` files, so both
models failed to load on a 16 KB device while every other part of the app worked. No
unit test could catch it — Robolectric never loads native code — and lint has no check
for it.

1.0.1 moves to `tasks-vision:0.10.26.1` and `com.google.ai.edge.litert:litert:1.4.0`,
both 16 KB aligned, and adds a `verifyNativeLibAlignment` Gradle task that parses the
ELF program headers of every packaged `.so` and fails `assembleRelease` if any 64-bit
library is under 16 KB. A dependency bump can no longer reintroduce this silently.

LiteRT is the maintained successor to `tensorflow-lite` and keeps the identical
`org.tensorflow.lite.Interpreter` API, so the swap needed no code changes.

## Building

Needs JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0).

```bash
./gradlew assembleRelease
```

Produces three APKs in `app/build/outputs/apk/release/`: arm64, arm32, and universal.
They're signed with the checked-in `app/sotto-sideload.jks` — a throwaway key so
sideloaded builds upgrade in place. **It is not a secret and must not be used for
anything but this proof of concept.**

## Third-party

- **Inter** typeface — SIL Open Font License 1.1, see `licenses/Inter-OFL.txt`
- **MediaPipe** BlazeFace short-range — Apache 2.0
- **FaceNet** TFLite — derived from the MIT-licensed FaceNet reference implementation

Sound cues and all iconography were generated for this project;
`tools/make_sfx.py` regenerates the audio.
