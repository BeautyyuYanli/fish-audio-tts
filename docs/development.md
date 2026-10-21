# Development

For installation and everyday use, see the [user guide](usage.md).

Android applications call a Kotlin `TextToSpeechService`. It reads protected native configuration and contacts Fish Audio independently of the Activity and WebView. The English settings panel uses Vite, Vue, TypeScript and Capacitor; its [design system](design-system.md) has primitive and semantic token layers.

## Build and install

Requirements: Node.js 22.12 or later, npm, JDK 21, Android SDK Platform 36, Build Tools 36, and platform-tools. Android 7.0 / API 24 or later is supported; target SDK is 36.

```sh
npm ci
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
npm run android:debug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

`android:debug` runs the Vue type check, Vite production build, Capacitor sync, and Gradle debug build. The debug APK uses the local Android debug signing key. For distribution, configure your own release signing in Android Studio.

Useful individual commands:

```sh
npm run typecheck
npm run build
npm run test:ui
npm run android:sync
npm run android:test
npm run android:lint
# With an emulator or device attached:
npm run android:device-test
```

Install Chromium once with `npx playwright install chromium` before running `test:ui`. It builds the production panel, checks test types, and runs browser behavior/layout checks against port 4173. Its controlled bridge fixtures do not contact Fish or prove native persistence. Device tests use the actual Android bridge/store; run them on a dedicated emulator and pass `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to the scoped Gradle task to keep the installed packages afterwards. See [verification details](verification.md) for commands and live-test limits.

For a browser-only view, run `npm run dev -- --host 127.0.0.1`. Native configuration, synthesis and system settings controls are disabled in browsers. There is no browser TTS fallback. A production preview can be started with `npm run preview -- --host 127.0.0.1` after building.

The Vite and Capacitor CLIs created the project scaffolds. Application sources such as `android/app/build.gradle` and the manifest are maintained here; generated web assets, Capacitor configuration JSON, `capacitor.build.gradle`, `capacitor.settings.gradle`, lockfiles, Gradle output and APKs must be regenerated with their tools rather than edited.


## Configuration and voices

The panel and system service share AES-256-GCM configuration in credential-protected `noBackupFilesDir`, with the encryption key in Android Keystore. The bridge never returns the API key; an empty key save preserves it, and deletion preserves profiles/default selection. Backups, bridge logging and WebView debugging are disabled. The store is available after the first unlock following reboot. Older single-voice configurations migrate to one profile.

Each keyed profile mutation validates and persists that row independently inside a short native store lock. Up to 50 profiles are supported, including profiles sharing a Reference ID. Unrelated drafts are never submitted. The first saved profile becomes the initial default; deleting the default selects the first remaining profile. Existing explicit client Voice selections retain priority. Dirty/new non-default profiles must be saved before becoming the default. Preview selection is independent and uses saved values.

Saved profiles expose network Voices named `fish:<profile-id>:<reference-id>:<language-tag>`. Stable profile IDs distinguish rows sharing a reference. Supported tags are `en-US`, `zh-CN`, `ja-JP`, `ko-KR`, `de-DE`, `fr-FR`, `es-ES`, `pt-BR`, `it-IT`, `ru-RU`, `ar-SA`, `nl-NL` and `pl-PL`. ISO-639 two/three-letter language queries are supported; unknown languages and stale Voice names are rejected. Regional variants are not separately advertised; the reference voice determines the accent.

Android's TTS data check reports local readiness (a key and at least one profile), not remote authentication, and returns Android-format locale lists. Install-data opens configuration; no offline voice package exists. Settings and data handlers have explicit exported declarations; settings metadata is in `tts_engine.xml`. The bound service needs no running Activity, boot receiver or frontend.

Catalog expansion fetches current choices through the native plugin: the public `POST /v1/tts` model-header enum from `https://api.fish.audio/openapi.json` without authentication, or authenticated `GET /model?self=true&page_size=100&page_number=N` for created/cloned TTS voices. Choices display exact slugs and reverse upstream order across loaded pages; Upstream default stays first. Results live only in memory, are discarded on close/key changes, and never replace saved values on failure. Requests are bounded to 25 seconds and 8 MiB. Saving validates local shape; Fish controls account/model access.

## Android client integration

An Android developer can explicitly select this engine with `TextToSpeech(context, listener, "com.fishaudio.tts")`, or omit the package to use the system default. After initialization, inspect `tts.voices`, select the desired `fish:<profile-id>:<reference-id>:<locale>` Voice, set `tts.setSpeechRate(...)`, and call `tts.speak(...)` or `tts.synthesizeToFile(...)`. For Android 11+, the calling app needs package visibility:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.TTS_SERVICE" />
    </intent>
</queries>
```

Register an `UtteranceProgressListener` to observe `onDone`, `onStop`, or `onError`. Refresh voices after adding/removing profiles or changing a Reference ID. Editing a label, prefix or parameters preserves the profile's Voice identity; removed or replaced references invalidate previous names. Test an unavailable network and an invalid key to verify errors. Android clients receive decoded PCM; `synthesizeToFile` still produces a WAV file. Real Fish Opus synthesis through the system client to a file passed on an API 35 emulator. Audible playback and ordinary third-party reading applications still require physical-device verification; see [verification details](verification.md).


## Synthesis contract and limits

The native client sends `POST https://api.fish.audio/v1/tts` using the global bearer key and the selected profile. It starts with a deep copy of that row's generation parameters, then overlays supplied system values. The system Voice selects the profile/Reference ID; system text, locale and speech rate take priority. The outgoing `text` is exactly `profile.prefixText + systemText`, composed once inside native synthesis without trimming, adding a separator, or changing the original input. Old profiles without a prefix use an empty string. A system rate of 120 becomes `prosody.speed: 1.2`, replacing a table speed rather than multiplying it; other fields such as `prosody.volume` are preserved. Missing system values fall back to table values. Fields missing from both are omitted, allowing upstream defaults. A blank model omits the `model` header. No local temperature, normalization, latency, model or speed defaults are inserted.

Android normally supplies a speech rate even when an application has not explicitly set one. Its default rate therefore also takes priority over a table speed. System rates are converted from percent to a multiplier and forwarded without clamping; Fish's documented supported range is 0.5–2.0 and out-of-range requests may be rejected upstream. Android volume stays in Android's playback layer, separately from Fish's `prosody.volume` in decibels. Fish has no documented pitch parameter, so pitch requests cannot be applied. Transport always sends `format: "opus"` and `sample_rate: 48000`; `opus_bitrate: -1000` is inserted only when the profile does not supply it. Automatic mode delegates bitrate selection to Fish; no fixed bitrate or compression ratio is assumed. Explicit profile bitrates are preserved. Opus is lossy; lower bitrate trades fidelity for bandwidth, and encoding/page buffering can affect latency.

Ogg pages are read progressively, with checksum, page sequence, stream identity, mono Opus header, packet continuation and end-of-stream validation. Only the current page and packet are retained. Android's `MediaCodec` decodes each packet before the next network read; the engine removes Opus pre-skip and final granule padding, then emits only 48 kHz mono PCM16 through `SynthesisCallback.start(48000, ENCODING_PCM_16BIT, 1)` and `audioAvailable`. Callback chunks contain complete PCM frames and never exceed `getMaxBufferSize()`. The decoder is released on completion, stop and errors. Empty, corrupt, truncated, oversized, chained and multiplexed responses fail. Packets/metadata are limited to 64 KiB. Synthesis runs synchronously on Android's synthesis thread, including `error` and `done`; no callback escapes `onSynthesizeText`.

`onStop` sets an atomic cancellation flag and cancels the active OkHttp call without acquiring a synthesis lock. Requests have a 15-second connection/write timeout, a 30-second read timeout and a 120-second total call timeout. Encoded responses are bounded to 32 MiB and decoded PCM to 64 MiB; a decoder making no progress fails after five seconds. Original system text and each profile's prefix are independently bounded to 4,000 UTF-16 code units, so the combined outgoing text can contain up to 8,000. Redirects and automatic retries are disabled. HTTP failures use Android error categories; untrusted upstream response bodies and credentials are not displayed or logged. There is no offline synthesis, downloaded voice cache or automatic billing retry.


## Official references

- [Fish Audio TTS endpoint](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech), [live OpenAPI schema](https://api.fish.audio/openapi.json), [voice listing and self filter](https://docs.fish.audio/api-reference/endpoint/model/list-models), [models](https://docs.fish.audio/developer-guide/models-pricing/models-overview), [quickstart / Reference IDs](https://docs.fish.audio/developer-guide/getting-started/quickstart).
- [Capacitor setup](https://capacitorjs.com/docs/getting-started/environment-setup), [local Android plugins](https://capacitorjs.com/docs/android/custom-code), [Android plugin API](https://capacitorjs.com/docs/plugins/android), [native inset handling](https://github.com/ionic-team/capacitor/blob/8.5.3/core/system-bars.md).
- [Android TextToSpeechService](https://developer.android.com/reference/android/speech/tts/TextToSpeechService), [SynthesisCallback](https://developer.android.com/reference/android/speech/tts/SynthesisCallback), [Voice](https://developer.android.com/reference/android/speech/tts/Voice), [TTS data protocol](https://developer.android.com/reference/android/speech/tts/TextToSpeech.Engine), [Android Keystore](https://developer.android.com/privacy-and-security/keystore).
- [Android keyboard visibility and layout resizing](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility), [native HTML disclosure controls](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/details).
- [Android MediaCodec / Opus codec-specific data](https://developer.android.com/reference/android/media/MediaCodec), [Ogg Opus framing and trimming](https://www.rfc-editor.org/rfc/rfc7845), [Ogg framing and checksum](https://www.rfc-editor.org/rfc/rfc3533).

Implementation verification and remaining device/API checks are recorded in [docs/verification.md](verification.md).

## Licenses in distributions

`npm run build` and `npm run dev` first run `npm run licenses`. This generates `public/licenses/` from the root LICENSE/NOTICE, installed production npm dependencies and reviewed Android license sources. Vite copies these assets into `dist/`; Capacitor copies them into the APK. The app displays the full texts without leaving the page or contacting an external service.

Every debug and release assets merge runs `verify<Variant>Licenses` from `android/app/licenses.gradle`. It compares the resolved runtime artifacts and native projects with `licenses/catalog.json` and verifies that their full texts are in the synced assets. A dependency change or stale/missing notices fails the build. No license check runs inside the installed app; the viewer only reads local text on demand. See [license maintenance](../licenses/README.md) before updating dependencies. Do not edit generated notices.
