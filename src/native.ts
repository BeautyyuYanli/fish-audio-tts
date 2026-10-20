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

export interface Configuration {
  hasApiKey: boolean
  configured: boolean
  voices: VoiceProfile[]
  defaultVoiceId: string
}

export interface PreviewStatus {
  state: 'idle' | 'initializing' | 'synthesizing' | 'playing' | 'done' | 'stopped' | 'error'
  message: string
}

interface FishTtsPlugin {
  getConfiguration(): Promise<Configuration>
  saveApiKey(options: { apiKey: string }): Promise<Configuration>
  deleteApiKey(): Promise<Configuration>
  saveVoices(options: { voices: VoiceProfile[] }): Promise<Configuration>
  setDefaultVoice(options: { voiceId: string }): Promise<Configuration>
  preview(options: { text: string; voiceId: string; language: string; speed: number }): Promise<void>
  stop(): Promise<void>
  getPreviewStatus(): Promise<PreviewStatus>
  openTtsSettings(): Promise<void>
  addListener(event: 'previewStatus', listener: (status: PreviewStatus) => void): Promise<PluginListenerHandle>
}

export const nativeAvailable = Capacitor.getPlatform() === 'android'
export const FishTts = registerPlugin<FishTtsPlugin>('FishTts')
