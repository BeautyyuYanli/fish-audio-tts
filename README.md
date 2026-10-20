# Fish Audio TTS for Android

An Android system text-to-speech engine with a Vite, Vue and TypeScript settings panel packaged by Capacitor. The English panel uses native HTML tables and form controls, with no application CSS, UI library, theme, or decorative assets.

Android applications call the registered Kotlin `TextToSpeechService`. The service reads protected native configuration and contacts Fish Audio itself, so it works without an Activity or WebView running. The panel's preview is also a client of this system engine.

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
npm run android:sync
npm run android:test
npm run android:lint
# With an emulator or device attached:
npm run android:device-test
```

For a browser-only view, run `npm run dev -- --host 127.0.0.1`. Native configuration, synthesis and system settings controls are disabled in browsers. There is no browser TTS fallback. A production preview can be started with `npm run preview -- --host 127.0.0.1` after building.

The Vite and Capacitor CLIs created the project scaffolds. Application sources such as `android/app/build.gradle` and the manifest are maintained here; generated web assets, Capacitor configuration JSON, `capacitor.build.gradle`, `capacitor.settings.gradle`, lockfiles, Gradle output and APKs must be regenerated with their tools rather than edited.

## Configure Fish Audio

1. Open **Fish Audio TTS** on the Android device.
2. In the independent **Global configuration** form, enter a key from the [Fish Audio API dashboard](https://fish.audio/app/api-keys), then press **Save API key**. Do not put it in source files, `.env`, or `VITE_*` variables.
3. In the **Voice list** form, press **Add voice**. Each item has its own table with an optional label, **Voice / Reference ID**, optional model, **Prefix text**, and optional JSON generation parameters. Prefix text defaults to empty and is prepended literally to every request for that voice, including previews. Include a trailing space or newline if needed; whitespace is preserved and no separator is inserted. Open a voice on [Fish Audio](https://fish.audio) and copy its model ID. This voice ID is separate from the synthesis model. The [official quickstart](https://docs.fish.audio/developer-guide/getting-started/quickstart) explains where to copy it and links example voices. No Reference ID or credential is preconfigured.
4. Leave **Model** as **Upstream default**, or explicitly select **Drama 3**, **S2.1 Pro Free (development)**, **S2.1 Pro (production)**, or **S2 Pro** according to your account. Drama 3 sends `model: drama-3` to the same TTS endpoint, as documented in the [official release](https://fish.audio/blog/fish-audio-drama-3/). A blank model omits the header; the application does not fill a model default. Production usage can require credits and account access.
5. Leave generation parameters blank to omit them, or enter a JSON object such as `{"temperature":0.7,"prosody":{"speed":1.2,"volume":-3}}`. These are Fish's actual body parameters. Nested `prosody` values merge with system values; see the precedence rule below. Text, reference selection, model header and the Android-compatible audio format have dedicated handling and cannot be replaced through this JSON field.
6. Press **Save voice list**. Edit any item or use **Remove voice**, then save the list again to persist the changes. Up to 50 profiles can be saved, including profiles using the same Fish reference with different generation parameters.
7. In **Global configuration**, select **Default system voice** from the saved profiles. The selection saves immediately and applies when an Android client has not selected a particular voice. Old configurations initially retain the first profile as their default. The selected profile remains the default through edits and key changes; deleting it selects the first remaining profile, or no default if the list is empty. Existing clients that have already selected a Voice keep it; reinitialize the client or select the language again to obtain the current default.
8. Select a saved **Voice** and **Language** in the preview, choose **Speed** from 0.5 to 2.0 (default 1.0), enter text and press **Start preview**. The speed is applied through Android's `TextToSpeech.setSpeechRate`; 1.5 becomes Android rate 150 and then Fish `prosody.speed: 1.5`. **Pitch** is always disabled because Fish has no pitch parameter. The state reports initialization, synthesis, playback, completion or errors. **Stop preview** cancels the system request and playback.

The two forms save independently: saving a key preserves saved voices and unsaved voice edits; saving the voice list preserves the key. Changing the default system voice also preserves unsaved voice edits. A saved key is write-only from the panel: leaving its field blank preserves it, and successful saves clear the field. **Delete API key** atomically removes the credential from saved configuration while preserving encrypted voice profiles and their default selection. Saving only checks local data shape, not account authentication or voice access. A previously saved single-voice configuration is retained as one list item.

All configuration is stored as AES-256-GCM ciphertext in the app's private, credential-protected `noBackupFilesDir`. The encryption key is held by Android Keystore. Backups, Capacitor bridge logging and WebView debugging are disabled. Keys are never returned by `getConfiguration`, saved in browser storage, or placed in builds or logs. The service can use the store after the device's first unlock following a reboot. Uninstalling or clearing app data removes configuration.

## Choose the system engine

1. Press **Open system TTS settings**. On devices that do not handle the AOSP TTS settings action, the button opens Accessibility settings; search Android Settings for **Text-to-speech** if necessary.
2. Select **Fish Audio TTS** as the preferred engine. Confirm Android's engine-selection dialog if shown.
3. Select a supported language and play Android's sample. The engine needs an internet connection and the saved Fish credentials.

Each saved profile exposes one network Voice per supported locale, named `fish:<profile-id>:<reference-id>:<language-tag>`. A profile's stable ID distinguishes rows sharing a Fish Reference ID. Supported locales are `en-US`, `zh-CN`, `ja-JP`, `ko-KR`, `de-DE`, `fr-FR`, `es-ES`, `pt-BR`, `it-IT`, `ru-RU`, `ar-SA`, `nl-NL`, and `pl-PL`. This is a conservative subset of Fish's documented multilingual capability. ISO-639 two- and three-letter language queries are handled; unknown languages and deleted/stale/unknown Voice names are rejected. Locale variants and additional regional accents are not advertised. The reference voice determines the accent.

Android's **Check TTS data** action reports whether native credentials and at least one profile are configured, and returns the Android-format available/unavailable locale lists. It does not claim that the remote account has been authenticated. **Install TTS data** opens configuration because there is no offline voice package. The settings Activity is declared in `tts_engine.xml`, and all system-facing components have explicit exported declarations. The service is an ordinary bound TTS service and needs no running frontend, boot receiver, or foreground Activity.

## Verify from another application

After selecting the default engine, use a reading application that supports **Android system TTS** and choose its system-engine mode. Close the Fish Audio panel and request English or Chinese speech from that app. Check rate changes and its stop button. Then remove Fish Audio from Recents and repeat. To check a cold process, reboot, unlock the device, and request speech before opening Fish Audio TTS.

An Android developer can explicitly select this engine with `TextToSpeech(context, listener, "com.fishaudio.tts")`, or omit the package to use the system default. After initialization, inspect `tts.voices`, select the desired `fish:<profile-id>:<reference-id>:<locale>` Voice, set `tts.setSpeechRate(...)`, and call `tts.speak(...)` or `tts.synthesizeToFile(...)`. For Android 11+, the calling app needs package visibility:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.TTS_SERVICE" />
    </intent>
</queries>
```

Register an `UtteranceProgressListener` to observe `onDone`, `onStop`, or `onError`. Refresh voices after adding/removing profiles or changing a Reference ID. Editing a label, prefix or parameters preserves the profile's Voice identity; removed or replaced references invalidate previous names. Test an unavailable network and an invalid key to verify errors. Real Fish HTTPS synthesis through Android's system client to a PCM WAV file has passed on an API 35 emulator. Audible playback and ordinary third-party reading applications still require physical-device verification; see [verification details](docs/verification.md).

## Synthesis contract and limits

The native client sends `POST https://api.fish.audio/v1/tts` using the global bearer key and the selected profile. It starts with a deep copy of that row's generation parameters, then overlays supplied system values. The system Voice selects the profile/Reference ID; system text, locale and speech rate take priority. The outgoing `text` is exactly `profile.prefixText + systemText`, composed once inside native synthesis without trimming, adding a separator, or changing the original input. Old profiles without a prefix use an empty string. A system rate of 120 becomes `prosody.speed: 1.2`, replacing a table speed rather than multiplying it; other fields such as `prosody.volume` are preserved. Missing system values fall back to table values. Fields missing from both are omitted, allowing upstream defaults. A blank model omits the `model` header. No local temperature, normalization, latency, model or speed defaults are inserted.

Android normally supplies a speech rate even when an application has not explicitly set one. Its default rate therefore also takes priority over a table speed. System rates are converted from percent to a multiplier and forwarded without clamping; Fish's documented supported range is 0.5–2.0 and out-of-range requests may be rejected upstream. Android volume stays in Android's playback layer, separately from Fish's `prosody.volume` in decibels. Fish has no documented pitch parameter, so pitch requests cannot be applied. `format: "wav"` and `sample_rate: 24000` are fixed transport requirements for the PCM callback contract, rather than optional generation defaults.

WAV headers are parsed and validated for the actual requested sample rate, PCM encoding, bit depth and channel count. RIFF metadata and frame boundaries are handled before emitting only PCM to `SynthesisCallback.start(24000, ENCODING_PCM_16BIT, 1)` and `audioAvailable`. Chunk sizes never exceed `getMaxBufferSize()`. Incorrect, empty or truncated audio fails rather than being played as PCM. Streaming WAV data sizes of zero or UINT32_MAX are accepted, as is Fish's observed pair of RIFF length `0xffffff24` and data length `0xffffff00`. These streaming responses are read to EOF with the limit applied to actual audio bytes; other finite lengths are checked strictly. Synthesis runs synchronously on Android's synthesis thread, including `error` and `done`; no callback escapes `onSynthesizeText`.

`onStop` sets an atomic cancellation flag and cancels the active OkHttp call without acquiring a synthesis lock. Requests have a 15-second connection/write timeout, a 30-second read timeout and a 120-second total call timeout. Responses are bounded to 32 MiB; original system text and each profile's prefix are independently bounded to 4,000 UTF-16 code units, so the combined outgoing text can contain up to 8,000. Redirects and automatic retries are disabled. HTTP failures use Android error categories; untrusted upstream response bodies and credentials are not displayed or logged. There is no offline synthesis, downloaded voice cache or automatic billing retry.

## Official references

- [Fish Audio TTS endpoint](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech), [OpenAPI schema including ProsodyControl](https://docs.fish.audio/api-reference/openapi.json), [models](https://docs.fish.audio/developer-guide/models-pricing/models-overview), [quickstart / Reference IDs](https://docs.fish.audio/developer-guide/getting-started/quickstart).
- [Capacitor setup](https://capacitorjs.com/docs/getting-started/environment-setup), [local Android plugins](https://capacitorjs.com/docs/android/custom-code), [Android plugin API](https://capacitorjs.com/docs/plugins/android), [native inset handling](https://github.com/ionic-team/capacitor/blob/8.5.3/core/system-bars.md).
- [Android TextToSpeechService](https://developer.android.com/reference/android/speech/tts/TextToSpeechService), [SynthesisCallback](https://developer.android.com/reference/android/speech/tts/SynthesisCallback), [Voice](https://developer.android.com/reference/android/speech/tts/Voice), [TTS data protocol](https://developer.android.com/reference/android/speech/tts/TextToSpeech.Engine), [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

Implementation verification and remaining device/API checks are recorded in [docs/verification.md](docs/verification.md).
