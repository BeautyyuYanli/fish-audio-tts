<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { CatalogOption, CatalogPage } from './catalog'

const props = withDefaults(defineProps<{
  id: string
  fieldName: string
  label: string
  modelValue: string
  disabled: boolean
  revision: number
  load: (page: number) => Promise<CatalogPage>
  initialOptions?: CatalogOption[]
  emptyText?: string
}>(), { initialOptions: () => [], emptyText: 'No voices found in the current workspace.' })
const emit = defineEmits<{
  'update:modelValue': [value: string]
  select: [option: CatalogOption]
}>()
const details = ref<HTMLDetailsElement>()
const items = ref<CatalogOption[]>([])
// Reverse all loaded upstream pages together; local defaults stay at the beginning.
const choices = computed(() => [...props.initialOptions, ...[...items.value].reverse()])
const loading = ref(false)
const error = ref('')
const hasMore = ref(false)
let nextPage = 1
let generation = 0
let disposed = false

/** Each expansion starts fresh; close, account changes and unmount invalidate late results. */
function reset() {
  generation++
  loading.value = false
  error.value = ''
  hasMore.value = false
  nextPage = 1
  items.value = []
}

async function loadNext() {
  if (loading.value || props.disabled || !details.value?.open) return
  const token = generation
  loading.value = true
  error.value = ''
  try {
    const page = await props.load(nextPage)
    if (disposed || token !== generation) return
    const combined = new Map(items.value.map((item) => [item.value, item]))
    for (const item of page.items) combined.set(item.value, item)
    items.value = [...combined.values()]
    hasMore.value = page.hasMore
    nextPage++
  } catch (failure) {
    if (!disposed && token === generation) error.value = failure instanceof Error ? failure.message : 'Unable to load choices. Please retry.'
  } finally {
    if (!disposed && token === generation) loading.value = false
  }
}

function toggle() {
  reset()
  if (details.value?.open && !props.disabled) void loadNext()
}

function choose(option: CatalogOption) {
  emit('update:modelValue', option.value)
  emit('select', option)
  if (details.value) details.value.open = false
  reset()
}

watch([() => props.disabled, () => props.revision], () => {
  if (details.value) details.value.open = false
  reset()
})
onUnmounted(() => { disposed = true; reset() })
</script>

<template>
  <details :id="id" ref="details" class="remote-picker" data-remote-picker @toggle="toggle">
    <summary class="disclosure" :aria-label="fieldName + ': ' + label" :aria-disabled="disabled" :tabindex="disabled ? -1 : 0" @click="disabled && $event.preventDefault()"><span class="summary-copy">{{ label }}</span></summary>
    <div class="remote-content" :aria-busy="loading">
      <p v-if="loading" class="notice" role="status">Loading…</p>
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <div v-if="choices.length" class="remote-options">
        <button v-for="item in choices" :key="item.value" class="remote-option" type="button" :data-option-value="item.value" :aria-pressed="item.value === modelValue" :disabled="disabled" @click="choose(item)">
          <span class="option-copy">{{ item.label }}<span v-if="item.label !== item.value" class="option-id">{{ item.value }}</span></span>
          <span v-if="item.value === modelValue" class="badge">Selected</span>
        </button>
      </div>
      <p v-if="!loading && !error && !choices.length" class="notice">{{ emptyText }}</p>
      <p v-if="error" class="action-stack"><button class="button button-block" type="button" :disabled="disabled || loading" @click="loadNext">Retry</button></p>
      <p v-else-if="hasMore" class="action-stack"><button class="button button-block" type="button" :disabled="disabled || loading" @click="loadNext">Load more</button></p>
    </div>
  </details>
</template>
