# User guide

Use Fish Audio voices in reading apps and other Android apps that support system text-to-speech. Save your API key, add a voice, then choose **Fish Audio TTS** as Android's preferred speech engine.

The settings panel is in English. Once configured, the engine works while the panel is closed.

## What you need

- An Android device running Android 7.0 or later.
- A [Fish Audio account and API key](https://fish.audio/app/api-keys), with access and sufficient credits for the voices and synthesis models you use.
- An internet connection whenever speech is generated.
- For reading aloud, an app that supports **Android system TTS**.

Fish Audio TTS is an independent, unofficial client and is not affiliated with, endorsed by, or sponsored by Fish Audio. You use your own account; Fish Audio's service charges and terms apply.

## Install

Install the project's APK on your Android device. If Android asks, allow installation from the app you used to open the APK.

To update, install the newer APK over the existing app using the same signing key. Uninstalling first removes your saved settings. If you want to build an APK yourself, follow the [build instructions](development.md#build-and-install).

## Add your key and a voice

1. Open **Fish Audio TTS** and expand **Global configuration**. Enter your API key and press **Save API key**.
2. Under **Voice list**, press **Add voice**. Give it an optional label.
3. Open **Choose your voice** to load voices you created or cloned in your Fish Audio workspace. Select one to fill its **Reference ID**. Use **Load more** if needed. You can also paste the ID of a public or otherwise accessible voice manually. Favorites are not included in this picker.
4. Leave **Model** set to **Upstream default**, or open the picker to load currently advertised synthesis model slugs. A model listed by Fish may still require separate account access or credits.
5. Press **Save voice** inside that voice's editor.
6. Select the radio beside the saved voice in the list to make it the system default. The **Default** badge confirms the saved selection.

Each voice saves separately. You can add several profiles, including different settings for the same Reference ID. Save a new or edited voice before making it the default. Opening a voice's editor does not select it.

The key field clears after a successful save. Its saved-status message shows whether a key is stored. Leaving the field blank preserves the key; **Delete API key** removes it without removing your voices.

## Choose a synthesis mode

In **Global configuration → Synthesis mode**, choose **Original** or **Continuous**. The selection saves immediately and applies to subsequent system speech and previews. Existing installations keep **Original** until you change it; mode changes preserve your key, saved voices and unsaved voice edits.

- **Original** generates each Android text segment independently over HTTP, using compressed Opus audio.
- **Continuous** streams successive segments into one Fish WebSocket generation session with `condition_on_previous_chunks: true`. Each segment sends `flush`. After receiving audio, a gap of 500 ms without new audio ends that Android segment; the connection stays available for three idle minutes. It uses uncompressed mono PCM16 at 44.1 kHz, which consumes more data than Opus. The completion rule is approximate: a long generation/network pause can cut a segment short or place delayed speech in the next segment.

Continuous context is reused only for the same calling app, saved voice, prefix and effective generation settings. Changing these, stopping speech, losing the connection or destroying the system service resets it. Switching back to Original closes a retained connection when the next request starts. Android or Fish may close a connection before the three-minute limit. Failed segments are not automatically replayed.

## Try the voice

In **Preview**, select a saved voice and a language, enter some text, then press **Start preview**. **Stop preview** cancels the request and playback.

**Speed** ranges from 0.5 to 2.0; 1.0 is normal speed. Pitch adjustment is unavailable. The Preview voice selection affects this preview only; the radio in **Voice list** chooses the system default.

Supported language choices are English, Chinese, Japanese, Korean, German, French, Spanish, Portuguese, Italian, Russian, Arabic, Dutch and Polish. The reference voice determines the accent and speech quality.

## Use it in your reading app

1. Press **Open system TTS settings**.
2. Choose **Fish Audio TTS** as the preferred engine and confirm Android's dialog if shown.
3. Choose a language and try Android's sample.
4. Open your reading app and select its **Android system TTS** mode. Start reading aloud.

The location and wording of Android's TTS settings vary by device. If the button opens Accessibility settings, search Android Settings for **Text-to-speech**.

You can close the Fish Audio TTS panel while another app reads. To check this, remove the panel from Recents and start speech again in your reader. After rebooting, unlock your device once before using the engine.

Some readers have their own speech engines. Select their Android system engine option to use this app. A reader that has already selected a particular voice may keep that choice after you change the default; restart its TTS session or select the language again.

## Adjust how a voice speaks

Expand a voice's **Advanced parameters**:

- **Prefix text** is prepended exactly to every utterance, including previews. Add a trailing space or newline if you need a separator. Instructions and expression tags depend on the chosen Fish model.
- **Generation parameters** accepts an optional JSON object with Fish's supported controls. For example, `{"temperature":0.6}` sets that value for this profile. Leave it blank to use upstream defaults. Save the voice after changing it.
- Original mode uses Opus with automatic bitrate. An optional `opus_bitrate` of `24000`, `32000`, `48000` or `64000` selects a fixed bitrate in bps; `-1000` selects automatic mode. Opus bitrate does not affect Continuous mode's PCM transport.

Values supplied by Android take priority over a voice's parameters, followed by Fish's defaults for omitted values. Android normally supplies a speech rate, so use your reader's rate control to adjust reading speed. Nested settings such as `prosody.volume` are preserved when Android overrides `prosody.speed`.

See [Fish Audio's generation parameters](https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech) for controls supported by your chosen model.

## Privacy and saved settings

Your API key and voice settings are saved in protected native Android storage, encrypted using a key held by Android Keystore. They are not saved in browser localStorage or included in the APK. Backups are disabled; clearing app data or uninstalling removes the configuration.

Speech text, including any prefix, is sent directly to Fish Audio using your account. The app has no intermediary server. Synthesis requires an internet connection; there are no offline voice downloads.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Speech synthesis fails | Check the saved API key, account credits, network, Reference ID and access to the selected model. Saving settings does not authenticate the account. |
| Your voice is missing from the picker | The picker shows created/cloned voices in the key's workspace. Paste another accessible Reference ID manually, or check the workspace/key. |
| The voice/model list does not load | Check the connection and saved key, then use **Retry** or reopen the picker. A failed lookup does not erase saved choices. |
| Android says voice data is missing | Save an API key and at least one voice in this app. There is no separate voice-data package to install. |
| A reader uses a different voice | Check the preferred engine, the reader's system TTS mode and its explicit voice selection. Restart its TTS session after changing the default. |
| Preview is disabled | Save the entered key and the selected voice's edits first. Other voice drafts do not block preview. |

When reporting a problem, include your Android version, app version and the steps that failed. Do not include your API key.

## License and development

The project is licensed under [Apache-2.0](../LICENSE). Third-party components retain their own licenses. Open **Open source licenses** at the bottom of the app to read the bundled license and attribution texts offline. The corresponding Public Suffix List source is also included in the APK.

The project license does not grant rights to Fish Audio's services, trademarks or other people's voices.

For contributors: [development and build instructions](development.md), [design system](design-system.md), [verification record](verification.md) and [license maintenance](../licenses/README.md).
