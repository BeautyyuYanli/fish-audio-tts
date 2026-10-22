import { Capacitor, registerPlugin } from '@capacitor/core'
import type { PluginListenerHandle } from '@capacitor/core'

/** The native store never returns the saved API key to the WebView. */
export interface VoiceProfile {
  id: string
  label: string
  referenceId: string
  model: string
  prefixText: string
  parameters: Record<string, unknown>
}

export type LanguageMode = 'system' | 'unset'

export interface Configuration {
  hasApiKey: boolean
  configured: boolean
  voices: VoiceProfile[]
  defaultVoiceId: string
  language: LanguageMode
}

export interface PreviewStatus {
  state: 'idle' | 'initializing' | 'synthesizing' | 'playing' | 'done' | 'stopped' | 'error'
  message: string
}

/** A new profile receives its stable identity natively; no other draft is returned or submitted. */
export interface SavedVoiceConfiguration extends Configuration { savedVoiceId: string }

export interface OwnVoice {
  id: string
  title: string
}

interface FishTtsPlugin {
  getConfiguration(): Promise<Configuration>
  saveApiKey(options: { apiKey: string }): Promise<Configuration>
  deleteApiKey(): Promise<Configuration>
  saveLanguage(options: { language: LanguageMode }): Promise<Configuration>
  saveVoice(options: { voice: VoiceProfile }): Promise<SavedVoiceConfiguration>
  removeVoice(options: { voiceId: string }): Promise<Configuration>
  setDefaultVoice(options: { voiceId: string }): Promise<Configuration>
  getSynthesisModels(): Promise<{ models: string[] }>
  getOwnVoices(options: { pageNumber: number }): Promise<{ voices: OwnVoice[]; hasMore: boolean }>
  preview(options: { text: string; voiceId: string; language: string; speed: number }): Promise<void>
  stop(): Promise<void>
  getPreviewStatus(): Promise<PreviewStatus>
  openTtsSettings(): Promise<void>
  addListener(event: 'previewStatus', listener: (status: PreviewStatus) => void): Promise<PluginListenerHandle>
}

export const nativeAvailable = Capacitor.getPlatform() === 'android'
export const FishTts = registerPlugin<FishTtsPlugin>('FishTts')
