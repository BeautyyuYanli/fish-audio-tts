import { FishTts } from './native'

export interface CatalogOption { value: string; label: string }
export interface CatalogPage { items: CatalogOption[]; hasMore: boolean }

/** Metadata is fetched on opening the picker, kept in memory, and never includes the native key. */
export async function loadSynthesisModels(): Promise<CatalogPage> {
  const { models } = await FishTts.getSynthesisModels()
  return { items: models.map((slug) => ({ value: slug, label: slug })), hasMore: false }
}

export async function loadOwnVoices(pageNumber: number): Promise<CatalogPage> {
  const { voices, hasMore } = await FishTts.getOwnVoices({ pageNumber })
  return { items: voices.map((voice) => ({ value: voice.id, label: voice.title })), hasMore }
}
