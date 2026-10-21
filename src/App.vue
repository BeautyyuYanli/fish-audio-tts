<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { PluginListenerHandle } from '@capacitor/core'
import { FishTts, nativeAvailable } from './native'
import type { Configuration, PreviewStatus, VoiceProfile } from './native'
import RemotePicker from './RemotePicker.vue'
import OpenSourceLicenses from './OpenSourceLicenses.vue'
import { loadOwnVoices, loadSynthesisModels } from './catalog'
import type { CatalogOption } from './catalog'

interface VoiceDraft {
  key: number
  id: string
  label: string
  referenceId: string
  model: string
  prefixText: string
  parametersText: string
  baseline: string | null
  expanded: boolean
  saving: boolean
  message: string
  tone: '' | 'error' | 'success'
}

const apiKey = ref('')
const hasApiKey = ref(false)
const globalOpen = ref(false)
const keyMessage = ref('')
const keyError = ref(false)
const savedVoices = ref<VoiceProfile[]>([])
const defaultVoiceId = ref('')
const defaultSelection = ref('')
const voices = ref<VoiceDraft[]>([])
const selectedVoiceId = ref('')
const language = ref('en-US')
const previewSpeed = ref<number | string>('1.0')
const loading = ref(nativeAvailable)
const mutationBusy = ref(false)
const startingPreview = ref(false)
const catalogRevision = ref(0)
const modelDefaults = [{ value: '', label: 'Upstream default' }]
const text = ref('Hello! This is Fish Audio speaking through the Android text-to-speech engine.')
const pageMessage = ref(nativeAvailable ? 'Loading configuration...' : 'Android is required to save configuration and use the system TTS engine.')
const preview = ref<PreviewStatus>({ state: 'idle', message: 'No preview yet.' })
const languages = [
  ['en-US', 'English'], ['zh-CN', 'Chinese'], ['ja-JP', 'Japanese'],
  ['ko-KR', 'Korean'], ['de-DE', 'German'], ['fr-FR', 'French'],
  ['es-ES', 'Spanish'], ['pt-BR', 'Portuguese'], ['it-IT', 'Italian'],
  ['ru-RU', 'Russian'], ['ar-SA', 'Arabic'], ['nl-NL', 'Dutch'], ['pl-PL', 'Polish'],
]
const active = computed(() => startingPreview.value || ['initializing', 'synthesizing', 'playing'].includes(preview.value.state))
const configured = computed(() => hasApiKey.value && savedVoices.value.length > 0)
const mutationDisabled = computed(() => !nativeAvailable || loading.value || mutationBusy.value || active.value)
const selectedDraftDirty = computed(() => voices.value.some((voice) => voice.id === selectedVoiceId.value && dirty(voice)))
let nextDraftKey = 0
let listener: PluginListenerHandle | undefined
let disposed = false

/** Compare editable text, including prefix whitespace; undoing an edit restores a clean row. */
function signature(voice: VoiceDraft) {
  return JSON.stringify([voice.label, voice.referenceId, voice.model, voice.prefixText, voice.parametersText])
}
function dirty(voice: VoiceDraft) { return voice.baseline === null || signature(voice) !== voice.baseline }
function voiceName(voice: Pick<VoiceDraft, 'label' | 'referenceId'>) { return voice.label.trim() || voice.referenceId || 'New voice' }
function shortName(voice: Pick<VoiceDraft, 'label' | 'referenceId'>) {
  const characters = Array.from(voiceName(voice))
  return characters.length > 24 ? characters.slice(0, 16).join('') + '…' + characters.slice(-7).join('') : characters.join('')
}
function fieldsDisabled(voice: VoiceDraft) { return loading.value || active.value || voice.saving }
function errorText(error: unknown) { return error instanceof Error ? error.message : 'The operation failed. Please try again.' }
function clearFeedback(voice: VoiceDraft) { voice.message = ''; voice.tone = '' }

function savedFields(profile: VoiceProfile) {
  return {
    id: profile.id, label: profile.label, referenceId: profile.referenceId, model: profile.model,
    prefixText: profile.prefixText,
    parametersText: Object.keys(profile.parameters).length ? JSON.stringify(profile.parameters, null, 2) : '',
  }
}
function makeDraft(profile?: VoiceProfile): VoiceDraft {
  const voice: VoiceDraft = {
    key: ++nextDraftKey, id: '', label: '', referenceId: '', model: '', prefixText: '', parametersText: '',
    baseline: null, expanded: !profile, saving: false, message: '', tone: '',
  }
  if (profile) { Object.assign(voice, savedFields(profile)); voice.baseline = signature(voice) }
  return voice
}

/**
 * Persisted metadata is independent of editor ownership. Mutations are serialized, but unrelated
 * fields stay editable; a reply never replaces another row's draft or expanded state.
 */
function applyConfiguration(config: Configuration) {
  if (disposed) return
  hasApiKey.value = config.hasApiKey
  savedVoices.value = config.voices
  defaultVoiceId.value = config.defaultVoiceId
  defaultSelection.value = config.defaultVoiceId
  if (!config.voices.some((voice) => voice.id === selectedVoiceId.value)) selectedVoiceId.value = config.defaultVoiceId
}

async function saveApiKey() {
  if (mutationDisabled.value) return
  if (!apiKey.value.trim() && !hasApiKey.value) {
    keyError.value = true; keyMessage.value = 'Enter a Fish Audio API key.'; return
  }
  mutationBusy.value = true
  keyError.value = false
  try {
    applyConfiguration(await FishTts.saveApiKey({ apiKey: apiKey.value.trim() }))
    if (disposed) return
    catalogRevision.value++
    apiKey.value = ''
    keyMessage.value = 'API key saved in protected native storage.'
  } catch (error) { keyError.value = true; keyMessage.value = errorText(error) }
  finally { mutationBusy.value = false }
}

async function deleteApiKey() {
  if (mutationDisabled.value) return
  mutationBusy.value = true
  keyError.value = false
  try {
    applyConfiguration(await FishTts.deleteApiKey())
    if (disposed) return
    catalogRevision.value++
    apiKey.value = ''
    keyMessage.value = 'API key deleted. Saved voices were preserved.'
  } catch (error) { keyError.value = true; keyMessage.value = errorText(error) }
  finally { mutationBusy.value = false }
}

function addVoice() {
  if (!loading.value && !active.value && voices.value.length < 50) voices.value.push(makeDraft())
}
function selectOwnVoice(voice: VoiceDraft, option: CatalogOption) {
  if (!voice.label.trim()) voice.label = option.label
  clearFeedback(voice)
}

async function saveVoice(voice: VoiceDraft) {
  if (mutationDisabled.value) return
  clearFeedback(voice)
  try {
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(voice.referenceId.trim())) throw new Error('Enter a valid Fish Audio Reference ID.')
    if (voice.prefixText.length > 4000) throw new Error('Prefix text must contain at most 4000 characters.')
    let parameters: unknown
    try { parameters = voice.parametersText.trim() ? JSON.parse(voice.parametersText) : {} }
    catch { throw new Error('Generation parameters must be a valid JSON object.') }
    if (parameters === null || Array.isArray(parameters) || typeof parameters !== 'object') {
      throw new Error('Generation parameters must be a JSON object.')
    }
    voice.saving = true
    mutationBusy.value = true
    const result = await FishTts.saveVoice({ voice: {
      id: voice.id, label: voice.label.trim(), referenceId: voice.referenceId.trim(), model: voice.model,
      prefixText: voice.prefixText, parameters: parameters as Record<string, unknown>,
    } })
    if (disposed) return
    const saved = result.voices.find((profile) => profile.id === result.savedVoiceId)
    if (!saved) throw new Error('Unable to read the saved voice. Reload the application.')
    applyConfiguration(result)
    Object.assign(voice, savedFields(saved))
    voice.baseline = signature(voice)
    voice.message = 'Voice saved.'
    voice.tone = 'success'
  } catch (error) { voice.message = errorText(error); voice.tone = 'error' }
  finally { voice.saving = false; mutationBusy.value = false }
}

async function removeVoice(voice: VoiceDraft) {
  if (mutationDisabled.value && voice.id) return
  if (voice.saving || active.value) return
  clearFeedback(voice)
  if (voice.id) {
    mutationBusy.value = true
    voice.saving = true
    try {
      applyConfiguration(await FishTts.removeVoice({ voiceId: voice.id }))
    } catch (error) {
      voice.message = errorText(error); voice.tone = 'error'; return
    } finally { voice.saving = false; mutationBusy.value = false }
  }
  if (!disposed) voices.value = voices.value.filter((draft) => draft.key !== voice.key)
}

/** The radio's pending selection is separate from the confirmed Default badge; failures roll back. */
async function setDefaultVoice(voice: VoiceDraft) {
  if (mutationDisabled.value || !voice.id || dirty(voice)) { defaultSelection.value = defaultVoiceId.value; return }
  mutationBusy.value = true
  clearFeedback(voice)
  voice.message = 'Saving default voice...'
  try {
    applyConfiguration(await FishTts.setDefaultVoice({ voiceId: voice.id }))
    voice.message = 'Default system voice saved.'
    voice.tone = 'success'
  } catch (error) {
    defaultSelection.value = defaultVoiceId.value
    voice.message = errorText(error); voice.tone = 'error'
  } finally { mutationBusy.value = false }
}

async function startPreview() {
  if (mutationDisabled.value || !configured.value || selectedDraftDirty.value || apiKey.value) return
  if (!text.value.trim() || text.value.length > 4000) {
    preview.value = { state: 'error', message: 'Enter between 1 and 4000 characters of preview text.' }; return
  }
  const speed = Number(previewSpeed.value)
  if (!Number.isFinite(speed) || speed < 0.5 || speed > 2.0) {
    preview.value = { state: 'error', message: 'Choose a preview speed between 0.5 and 2.0.' }; return
  }
  startingPreview.value = true
  try { await FishTts.preview({ text: text.value, voiceId: selectedVoiceId.value, language: language.value, speed }) }
  catch (error) { if (!disposed) preview.value = { state: 'error', message: errorText(error) } }
  finally { startingPreview.value = false }
}
async function stopPreview() {
  try { await FishTts.stop() }
  catch (error) { preview.value = { state: 'error', message: errorText(error) } }
}
async function openSettings() {
  try { await FishTts.openTtsSettings() }
  catch (error) { pageMessage.value = errorText(error) }
}

onMounted(async () => {
  if (!nativeAvailable) return
  try {
    listener = await FishTts.addListener('previewStatus', (status) => { if (!disposed) preview.value = status })
    if (disposed) { await listener.remove(); return }
    const config = await FishTts.getConfiguration()
    if (disposed) return
    applyConfiguration(config)
    voices.value = config.voices.map(makeDraft)
    preview.value = await FishTts.getPreviewStatus()
    pageMessage.value = 'Configuration loaded.'
  } catch (error) { pageMessage.value = errorText(error) }
  finally { loading.value = false }
})
onUnmounted(() => { disposed = true; apiKey.value = ''; void listener?.remove() })
</script>

<template>
  <main class="app-shell">
    <header class="app-header">
      <h1>Fish Audio TTS</h1>
      <p class="subtitle">Android text-to-speech engine.</p>
    </header>

    <details id="global-configuration" class="panel" :open="globalOpen" @toggle="globalOpen = ($event.target as HTMLDetailsElement).open">
      <summary class="disclosure">
        <span class="summary-copy">Global configuration<span class="helper">{{ hasApiKey ? 'API key saved' : 'No API key saved' }}</span></span>
      </summary>
      <form class="panel-body" novalidate @submit.prevent="saveApiKey">
        <table class="fields" role="presentation"><tbody><tr><td>
          <label for="api-key">API key</label>
          <input id="api-key" v-model="apiKey" type="password" autocomplete="off" autocapitalize="off" spellcheck="false" :disabled="mutationDisabled" :placeholder="hasApiKey ? 'Keep saved key' : 'Enter API key'" />
        </td></tr></tbody></table>
        <div class="action-stack">
          <button id="save-api-key" class="button button-primary button-block" type="submit" :disabled="mutationDisabled">Save API key</button>
          <button class="button button-danger button-block" type="button" :disabled="mutationDisabled" @click="deleteApiKey">Delete API key</button>
        </div>
        <p v-if="keyMessage" class="notice" :class="{ error: keyError, success: !keyError }" :role="keyError ? 'alert' : 'status'">{{ keyMessage }}</p>
      </form>
    </details>

    <section class="section" aria-labelledby="voices-heading">
      <div class="section-heading">
        <h2 id="voices-heading">Voice list</h2>
        <p class="helper">Select the default for system speech.</p>
      </div>
      <fieldset class="voice-list">
        <legend class="visually-hidden">Default system voice</legend>
        <p v-if="!voices.length" class="muted">No voices. Add a voice to get started.</p>
        <article v-for="voice in voices" :key="voice.key" class="voice-card" :data-voice="voice.key" :data-voice-id="voice.id" :data-dirty="dirty(voice)">
          <header class="voice-header">
            <label class="default-choice" :title="'Use ' + voiceName(voice) + ' as the system default'">
              <input v-model="defaultSelection" type="radio" name="system-default-voice" :value="voice.id || 'draft-' + voice.key" data-action="default-voice"
                :aria-label="'Use ' + voiceName(voice) + ' as the system default'" :disabled="mutationDisabled || !voice.id || dirty(voice)" @change="setDefaultVoice(voice)" />
            </label>
            <button class="voice-toggle disclosure" type="button" data-action="expand-voice" :aria-expanded="voice.expanded" :aria-controls="'voice-editor-' + voice.key" @click="voice.expanded = !voice.expanded">
              <span class="summary-copy">
                <span class="voice-title"><span class="voice-name">{{ voiceName(voice) }}</span><span v-if="voice.id && voice.id === defaultVoiceId" class="badge">Default</span></span>
                <span v-if="dirty(voice)" class="dirty-marker">Unsaved changes</span>
              </span>
            </button>
          </header>
          <form :id="'voice-editor-' + voice.key" class="voice-body" :hidden="!voice.expanded" novalidate @submit.prevent="saveVoice(voice)" @input="clearFeedback(voice)">
            <p v-if="dirty(voice) && voice.id !== defaultVoiceId" class="helper">Save this voice before selecting it as default.</p>
            <table class="fields" role="presentation"><tbody>
              <tr><td><label :for="'label-' + voice.key">Label</label>
                <input :id="'label-' + voice.key" v-model="voice.label" type="text" maxlength="100" :disabled="fieldsDisabled(voice)" />
              </td></tr>
              <tr><td><label :for="'reference-' + voice.key">Reference ID</label>
                <input :id="'reference-' + voice.key" v-model="voice.referenceId" data-field="referenceId" type="text" autocomplete="off" autocapitalize="off" spellcheck="false" maxlength="128" :disabled="fieldsDisabled(voice)" />
                <RemotePicker :id="'own-voice-' + voice.key" v-model="voice.referenceId" field-name="Your voices" label="Choose your voice" :disabled="fieldsDisabled(voice) || !nativeAvailable || !voice.expanded" :revision="catalogRevision" :load="loadOwnVoices" @select="selectOwnVoice(voice, $event)" />
                <p class="helper">Your created and cloned voices.</p>
              </td></tr>
              <tr><td><span class="field-label">Model</span>
                <RemotePicker :id="'model-' + voice.key" v-model="voice.model" field-name="Model" :label="voice.model || 'Upstream default'" :disabled="fieldsDisabled(voice) || !nativeAvailable || !voice.expanded" :revision="catalogRevision" :load="loadSynthesisModels" :initial-options="modelDefaults" @update:model-value="clearFeedback(voice)" />
              </td></tr>
            </tbody></table>
            <details class="advanced">
              <summary class="disclosure">Advanced parameters</summary>
              <div class="advanced-body">
                <table class="fields" role="presentation"><tbody>
                  <tr><td><label :for="'prefix-' + voice.key">Prefix text</label>
                    <textarea :id="'prefix-' + voice.key" v-model="voice.prefixText" data-field="prefixText" rows="2" maxlength="4000" :disabled="fieldsDisabled(voice)" />
                    <p class="helper">Prepended exactly as entered.</p>
                  </td></tr>
                  <tr><td><label :for="'parameters-' + voice.key">Generation parameters</label>
                    <textarea :id="'parameters-' + voice.key" v-model="voice.parametersText" class="code-field" data-field="parameters" rows="8" maxlength="16384" autocomplete="off" autocapitalize="off" spellcheck="false" :disabled="fieldsDisabled(voice)" />
                    <p class="helper">Optional JSON; omitted values use upstream defaults.</p>
                  </td></tr>
                </tbody></table>
                <p class="helper">System values override these parameters.</p>
              </div>
            </details>
            <footer class="row-footer">
              <button class="button button-primary button-block" type="submit" data-action="save-voice" :disabled="mutationDisabled">{{ voice.saving ? 'Saving…' : 'Save voice' }}</button>
              <div class="remove-action"><button class="button button-danger button-block" type="button" data-action="remove-voice" :disabled="fieldsDisabled(voice) || (mutationBusy && !!voice.id)" @click="removeVoice(voice)">Remove voice</button></div>
            </footer>
          </form>
          <p v-if="voice.message" class="voice-feedback notice" :class="voice.tone" :role="voice.tone === 'error' ? 'alert' : 'status'">{{ voice.message }}</p>
        </article>
      </fieldset>
      <div class="action-stack"><button id="add-voice" class="button button-block" type="button" :disabled="loading || active || voices.length >= 50" @click="addVoice">Add voice</button></div>
      <details class="rules">
        <summary class="disclosure">Generation rules</summary>
        <p class="helper">System values override table values. Nested prosody values are merged. Omitted values use upstream defaults.</p>
        <p class="helper">Android supplies a speech rate, including its default. Pitch adjustment is unavailable; Android controls playback volume.</p>
        <p class="helper">Speech text is sent to Fish Audio using your saved key. Audio is streamed using Opus with automatic bitrate by default and decoded to 48,000 Hz mono PCM16. Set opus_bitrate in generation parameters to change the bitrate.</p>
        <p class="helper">Do not put text, reference_id, references, model, format or sample_rate in generation parameters.</p>
        <p class="helper"><a href="https://fish.audio" target="_blank" rel="noopener noreferrer">Find a voice / Reference ID</a></p>
        <p class="helper"><a href="https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech" target="_blank" rel="noopener noreferrer">Fish Audio generation parameters</a></p>
      </details>
    </section>

    <section class="section" aria-labelledby="preview-heading">
      <h2 id="preview-heading" class="section-heading">Preview</h2>
      <table class="fields" role="presentation"><tbody>
        <tr><td><label for="preview-voice">Voice</label>
          <select id="preview-voice" v-model="selectedVoiceId" :disabled="active || !savedVoices.length">
            <option v-if="!savedVoices.length" value="">No saved voices</option>
            <option v-for="voice in savedVoices" :key="voice.id" :value="voice.id" :title="voice.label || voice.referenceId">{{ shortName(voice) }}</option>
          </select><p class="helper">For this preview only.</p>
        </td></tr>
        <tr><td><label for="preview-language">Language</label>
          <select id="preview-language" v-model="language" :disabled="active"><option v-for="entry in languages" :key="entry[0]" :value="entry[0]">{{ entry[1] }} ({{ entry[0] }})</option></select>
        </td></tr>
        <tr><td><label for="preview-text">Text</label><textarea id="preview-text" v-model="text" rows="4" maxlength="4000" :disabled="active" /></td></tr>
      </tbody></table>
      <div class="numeric-fields">
        <div><label for="preview-speed">Speed</label><input id="preview-speed" v-model.number="previewSpeed" type="number" min="0.5" max="2" step="0.1" :disabled="active" /><p class="helper">0.5–2.0; 1.0 is normal speed.</p></div>
        <div><label for="preview-pitch">Pitch</label><input id="preview-pitch" type="number" value="1.0" disabled /><p class="helper">Unavailable</p></div>
      </div>
      <p v-if="!configured" class="notice">Save an API key in Global configuration and add a voice to start.</p>
      <p v-else-if="apiKey" class="notice">Save the API key before starting a preview.</p>
      <p v-else-if="selectedDraftDirty" class="notice">Save this voice before starting a preview.</p>
      <div class="preview-actions">
        <button id="start-preview" class="button button-primary" type="button" :disabled="mutationDisabled || !configured || selectedDraftDirty || !!apiKey" @click="startPreview">Start preview</button>
        <button id="stop-preview" class="button" :class="{ 'button-primary': active }" type="button" :disabled="!nativeAvailable || !active" @click="stopPreview">Stop preview</button>
      </div>
      <div class="preview-status" :class="{ error: preview.state === 'error' }" role="status" aria-live="polite"><p class="status-heading">{{ preview.state }}</p><p class="helper">{{ preview.message }}</p></div>
      <div class="settings-action">
        <button class="button button-block" type="button" :disabled="!nativeAvailable" @click="openSettings">Open system TTS settings</button>
        <p class="helper">Select Fish Audio TTS as the preferred engine.</p>
      </div>
    </section>
    <p id="page-status" class="notice" role="status">{{ pageMessage }}</p>
    <OpenSourceLicenses />
  </main>
</template>
