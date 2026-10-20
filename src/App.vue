<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { PluginListenerHandle } from '@capacitor/core'
import { FishTts, nativeAvailable } from './native'
import type { Configuration, PreviewStatus, VoiceProfile } from './native'

interface VoiceDraft {
  key: number
  id: string
  label: string
  referenceId: string
  model: string
  prefixText: string
  parametersText: string
}

const apiKey = ref('')
const hasApiKey = ref(false)
const savedVoices = ref<VoiceProfile[]>([])
const defaultVoiceId = ref('')
const voices = ref<VoiceDraft[]>([])
const selectedVoiceId = ref('')
const language = ref('en-US')
const previewSpeed = ref(1.0)
const busy = ref(nativeAvailable)
const dirtyVoices = ref(false)
const text = ref('Hello! This is Fish Audio speaking through the Android text-to-speech engine.')
const message = ref(nativeAvailable ? 'Loading configuration...' : 'Android is required to save configuration and use the system TTS engine.')
const preview = ref<PreviewStatus>({ state: 'idle', message: 'No preview yet.' })
const languages = [
  ['en-US', 'English'], ['zh-CN', 'Chinese'], ['ja-JP', 'Japanese'],
  ['ko-KR', 'Korean'], ['de-DE', 'German'], ['fr-FR', 'French'],
  ['es-ES', 'Spanish'], ['pt-BR', 'Portuguese'], ['it-IT', 'Italian'],
  ['ru-RU', 'Russian'], ['ar-SA', 'Arabic'], ['nl-NL', 'Dutch'], ['pl-PL', 'Polish'],
]
const active = computed(() => ['initializing', 'synthesizing', 'playing'].includes(preview.value.state))
const configured = computed(() => hasApiKey.value && savedVoices.value.length > 0)
let nextDraftKey = 0
let listener: PluginListenerHandle | undefined
let disposed = false

/** Key saves must not overwrite unsaved edits in the independent voice form. */
function applyConfiguration(config: Configuration, replaceDrafts = false) {
  hasApiKey.value = config.hasApiKey
  savedVoices.value = config.voices
  defaultVoiceId.value = config.defaultVoiceId
  if (!config.voices.some((voice) => voice.id === selectedVoiceId.value)) {
    selectedVoiceId.value = config.defaultVoiceId
  }
  if (replaceDrafts) {
    voices.value = config.voices.map((voice) => ({
      ...voice, key: ++nextDraftKey,
      parametersText: Object.keys(voice.parameters).length ? JSON.stringify(voice.parameters, null, 2) : '',
    }))
    dirtyVoices.value = false
  }
}

function showError(error: unknown) {
  message.value = error instanceof Error ? error.message : 'The operation failed. Please try again.'
}

async function saveApiKey() {
  if (!apiKey.value.trim() && !hasApiKey.value) {
    message.value = 'Enter a Fish Audio API key.'
    return
  }
  busy.value = true
  try {
    applyConfiguration(await FishTts.saveApiKey({ apiKey: apiKey.value.trim() }))
    apiKey.value = ''
    message.value = 'API key saved in protected native storage.'
  } catch (error) { showError(error) }
  finally { busy.value = false }
}

async function deleteApiKey() {
  busy.value = true
  try {
    applyConfiguration(await FishTts.deleteApiKey())
    apiKey.value = ''
    message.value = 'API key deleted. Saved voices were preserved.'
  } catch (error) { showError(error) }
  finally { busy.value = false }
}

function addVoice() {
  voices.value.push({ key: ++nextDraftKey, id: '', label: '', referenceId: '', model: '', prefixText: '', parametersText: '' })
  dirtyVoices.value = true
}

/** The global default is saved independently so a selection cannot discard an unsaved voice draft. */
async function setDefaultVoice(event: Event) {
  const select = event.target as HTMLSelectElement
  busy.value = true
  try {
    applyConfiguration(await FishTts.setDefaultVoice({ voiceId: select.value }))
    message.value = 'Default system voice saved.'
  } catch (error) {
    select.value = defaultVoiceId.value
    showError(error)
  } finally { busy.value = false }
}

function removeVoice(index: number) {
  voices.value.splice(index, 1)
  dirtyVoices.value = true
}

async function saveVoices() {
  const profiles: VoiceProfile[] = []
  for (const [index, voice] of voices.value.entries()) {
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(voice.referenceId.trim())) {
      message.value = 'Enter a valid Reference ID for Voice ' + (index + 1) + '.'
      return
    }
    if (voice.prefixText.length > 4000) {
      message.value = 'Prefix text for Voice ' + (index + 1) + ' must contain at most 4000 characters.'
      return
    }
    let parameters: unknown
    try { parameters = voice.parametersText.trim() ? JSON.parse(voice.parametersText) : {} }
    catch {
      message.value = 'Generation parameters for Voice ' + (index + 1) + ' must be a valid JSON object.'
      return
    }
    if (parameters === null || Array.isArray(parameters) || typeof parameters !== 'object') {
      message.value = 'Generation parameters for Voice ' + (index + 1) + ' must be a JSON object.'
      return
    }
    profiles.push({
      id: voice.id, label: voice.label.trim(), referenceId: voice.referenceId.trim(),
      model: voice.model, prefixText: voice.prefixText, parameters: parameters as Record<string, unknown>,
    })
  }
  busy.value = true
  try {
    applyConfiguration(await FishTts.saveVoices({ voices: profiles }), true)
    message.value = 'Voice list saved.'
  } catch (error) { showError(error) }
  finally { busy.value = false }
}

async function startPreview() {
  if (!text.value.trim() || text.value.length > 4000) {
    message.value = 'Enter between 1 and 4000 characters of preview text.'
    return
  }
  if (!Number.isFinite(previewSpeed.value) || previewSpeed.value < 0.5 || previewSpeed.value > 2.0) {
    message.value = 'Choose a preview speed between 0.5 and 2.0.'
    return
  }
  busy.value = true
  try { await FishTts.preview({ text: text.value, voiceId: selectedVoiceId.value, language: language.value, speed: previewSpeed.value }) }
  catch (error) { showError(error) }
  finally { busy.value = false }
}

async function stopPreview() {
  try { await FishTts.stop() }
  catch (error) { showError(error) }
}

async function openSettings() {
  try { await FishTts.openTtsSettings() }
  catch (error) { showError(error) }
}

onMounted(async () => {
  if (!nativeAvailable) return
  try {
    listener = await FishTts.addListener('previewStatus', (status) => { preview.value = status })
    if (disposed) { await listener.remove(); return }
    applyConfiguration(await FishTts.getConfiguration(), true)
    preview.value = await FishTts.getPreviewStatus()
    message.value = 'Configuration loaded. Save the global API key and voice list independently.'
  } catch (error) { showError(error) }
  finally { busy.value = false }
})

onUnmounted(() => {
  disposed = true
  apiKey.value = ''
  void listener?.remove()
})
</script>

<template>
  <main>
    <h1>Fish Audio TTS</h1>
    <p>Android network text-to-speech engine. Speech text is sent to Fish Audio using your saved API key.</p>

    <form novalidate @submit.prevent="saveApiKey">
      <fieldset :disabled="!nativeAvailable || busy || active">
        <legend>Global configuration</legend>
        <table>
          <caption>Fish Audio configuration</caption>
          <tbody>
            <tr><th scope="row"><label for="api-key">API key</label></th><td>
              <input id="api-key" v-model="apiKey" type="password" autocomplete="off" autocapitalize="off" spellcheck="false" :placeholder="hasApiKey ? 'Leave blank to keep saved key' : 'Enter API key'" />
              <p>{{ hasApiKey ? 'A key is saved in protected native storage.' : 'No API key saved.' }}</p>
            </td></tr>
            <tr><th scope="row">Actions</th><td>
              <button id="save-api-key" type="submit">Save API key</button>
              <button type="button" @click="deleteApiKey">Delete API key</button>
            </td></tr>
            <tr><th scope="row"><label for="default-voice">Default system voice</label></th><td>
              <select id="default-voice" :value="defaultVoiceId" :disabled="!savedVoices.length" @change="setDefaultVoice">
                <option v-if="!savedVoices.length" value="">No saved voices</option>
                <option v-for="voice in savedVoices" :key="voice.id" :value="voice.id">{{ voice.label || voice.referenceId }}</option>
              </select>
              <p>Used when an Android client has not selected a voice. Changes are saved immediately. Save new voices in the voice list first.</p>
            </td></tr>
          </tbody>
        </table>
      </fieldset>
    </form>

    <form novalidate @submit.prevent="saveVoices" @input="dirtyVoices = true" @change="dirtyVoices = true">
      <fieldset :disabled="busy || active">
        <legend>Voice list</legend>
        <p>Each saved item becomes a selectable Android network voice for every supported language. Choose the default system voice in Global configuration.</p>
        <p v-if="!voices.length">No voices. Add a voice to configure its Reference ID and generation parameters.</p>
        <fieldset v-for="(voice, index) in voices" :key="voice.key">
          <legend>Voice {{ index + 1 }}</legend>
          <table>
            <caption>{{ voice.label || 'Voice ' + (index + 1) }}</caption>
            <tbody>
              <tr><th scope="row"><label :for="'label-' + voice.key">Label</label></th><td>
                <input :id="'label-' + voice.key" v-model="voice.label" type="text" maxlength="100" />
              </td></tr>
              <tr><th scope="row"><label :for="'reference-' + voice.key">Voice / Reference ID</label></th><td>
                <input :id="'reference-' + voice.key" v-model="voice.referenceId" data-field="referenceId" type="text" autocomplete="off" autocapitalize="off" spellcheck="false" maxlength="128" />
                <p>Copy the model ID from a voice at <a href="https://fish.audio" target="_blank" rel="noopener noreferrer">fish.audio</a>.</p>
              </td></tr>
              <tr><th scope="row"><label :for="'model-' + voice.key">Model</label></th><td>
                <select :id="'model-' + voice.key" v-model="voice.model">
                  <option value="">Upstream default</option>
                  <option value="drama-3">Drama 3</option>
                  <option value="s2.1-pro-free">S2.1 Pro Free (development)</option>
                  <option value="s2.1-pro">S2.1 Pro (production)</option>
                  <option value="s2-pro">S2 Pro</option>
                </select>
              </td></tr>
              <tr><th scope="row"><label :for="'prefix-' + voice.key">Prefix text</label></th><td>
                <textarea :id="'prefix-' + voice.key" v-model="voice.prefixText" data-field="prefixText" rows="3" cols="26" maxlength="4000" />
                <p>Optional text prepended exactly as entered to every request for this voice. Include a trailing space or newline if needed.</p>
              </td></tr>
              <tr><th scope="row"><label :for="'parameters-' + voice.key">Generation parameters</label></th><td>
                <textarea :id="'parameters-' + voice.key" v-model="voice.parametersText" data-field="parameters" rows="6" cols="26" maxlength="16384" autocomplete="off" autocapitalize="off" spellcheck="false" />
                <p>Optional JSON object, for example <code>{"temperature":0.7,"prosody":{"speed":1.2,"volume":0}}</code>. Leave values out to use upstream defaults.</p>
                <p><a href="https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech" target="_blank" rel="noopener noreferrer">Fish Audio generation parameters</a></p>
              </td></tr>
              <tr><th scope="row">Actions</th><td><button type="button" @click="removeVoice(index)">Remove voice</button></td></tr>
            </tbody>
          </table>
        </fieldset>
        <p><button id="add-voice" type="button" :disabled="voices.length >= 50" @click="addVoice">Add voice</button>
          <button id="save-voices" type="submit" :disabled="!nativeAvailable">Save voice list</button></p>
      </fieldset>
    </form>

    <p>System generation values take priority over table values. Parameters missing from both are omitted so Fish Audio can apply its defaults. Nested prosody values are merged.</p>
    <p>Android supplies a speech rate, including its default rate. It overrides the row's speed after conversion from percent to a multiplier. Fish supports 0.5–2.0; unsupported values are sent to Fish for validation. Pitch adjustment is unavailable; Android controls playback volume.</p>
    <p>Audio output is managed by the engine: 24,000 Hz, mono, 16-bit PCM extracted from WAV. Do not put text, reference_id, references, model, format or sample_rate in generation parameters.</p>
    <p role="status" aria-live="polite">{{ message }}</p>
    <p v-if="dirtyVoices">Unsaved voice changes. Save the voice list before starting a preview.</p>
    <p v-if="apiKey">Unsaved API key. Save it before starting a preview.</p>

    <table>
      <caption>Preview using the Android system engine</caption>
      <tbody>
        <tr><th scope="row"><label for="preview-voice">Voice</label></th><td>
          <select id="preview-voice" v-model="selectedVoiceId" :disabled="active"><option v-for="voice in savedVoices" :key="voice.id" :value="voice.id">{{ voice.label || voice.referenceId }}</option></select>
        </td></tr>
        <tr><th scope="row"><label for="preview-language">Language</label></th><td>
          <select id="preview-language" v-model="language" :disabled="active"><option v-for="entry in languages" :key="entry[0]" :value="entry[0]">{{ entry[1] }} ({{ entry[0] }})</option></select>
        </td></tr>
        <tr><th scope="row"><label for="preview-text">Text</label></th><td><textarea id="preview-text" v-model="text" rows="5" cols="30" maxlength="4000" :disabled="active" /></td></tr>
        <tr><th scope="row"><label for="preview-speed">Speed</label></th><td>
          <input id="preview-speed" v-model.number="previewSpeed" type="number" min="0.5" max="2" step="0.1" :disabled="active" />
          <p>Multiplier from 0.5 to 2.0. 1.0 is normal speed.</p>
        </td></tr>
        <tr><th scope="row"><label for="preview-pitch">Pitch</label></th><td>
          <input id="preview-pitch" type="number" value="1.0" disabled />
          <p>Unavailable. Fish Audio has no pitch parameter.</p>
        </td></tr>
        <tr><th scope="row">Controls</th><td>
          <button id="start-preview" type="button" :disabled="!nativeAvailable || !configured || dirtyVoices || !!apiKey || busy || active" @click="startPreview">Start preview</button>
          <button type="button" :disabled="!nativeAvailable || (!active && !busy)" @click="stopPreview">Stop preview</button>
        </td></tr>
        <tr><th scope="row">State</th><td role="status" aria-live="polite">{{ preview.state }}: {{ preview.message }}</td></tr>
      </tbody>
    </table>
    <p><button type="button" :disabled="!nativeAvailable" @click="openSettings">Open system TTS settings</button></p>
    <p>Select Fish Audio TTS as your preferred engine in Android text-to-speech settings. An internet connection and a valid Fish Audio account are required.</p>
  </main>
</template>
