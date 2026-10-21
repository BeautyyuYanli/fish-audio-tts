<script setup lang="ts">
import { ref } from 'vue'

interface LicenseComponent {
  id: string
  name: string
  version?: string
  license: string
  packages: string[]
  notices: { title: string; text: string; url?: string }[]
  source?: { asset: string; url: string }
}

// Notices are bundled with the panel. Opening this disclosure never contacts
// Fish or leaves the page, so unsaved configuration remains intact.
// License coverage and integrity are checked by build tools, not by the app.
const components = ref<LicenseComponent[]>([])
const loading = ref(false)
const error = ref('')
async function load() {
  if (loading.value || components.value.length) return
  loading.value = true
  error.value = ''
  try {
    const response = await fetch(`${import.meta.env.BASE_URL}licenses/notices.json`)
    if (!response.ok) throw new Error('Missing bundled notices')
    const result = await response.json() as { components: LicenseComponent[] }
    components.value = result.components
  } catch {
    error.value = 'Unable to load the bundled licenses. Please try again.'
  } finally {
    loading.value = false
  }
}
function onToggle(event: Event) {
  if ((event.currentTarget as HTMLDetailsElement).open) void load()
}
</script>

<template>
  <details id="open-source-licenses" class="rules" @toggle="onToggle">
    <summary class="disclosure">Open source licenses</summary>
    <p class="helper">Fish Audio TTS is licensed under Apache-2.0. Third-party components retain their own licenses. All license texts below are included with the app and available offline.</p>
    <p v-if="loading" class="notice" role="status">Loading licenses…</p>
    <div v-if="error" class="notice error" role="alert">
      <p>{{ error }}</p>
      <button class="button" type="button" @click="load">Retry</button>
    </div>
    <details v-for="component in components" :key="component.id" class="license-component">
      <summary class="disclosure">{{ component.name + (component.version ? ' ' + component.version : '') }} · {{ component.license }}</summary>
      <ul v-if="component.packages.length" class="license-packages helper"><li v-for="name in component.packages" :key="name">{{ name }}</li></ul>
      <template v-for="(notice, index) in component.notices" :key="index">
        <p class="helper">{{ notice.title }}</p>
        <pre class="license-text">{{ notice.text }}</pre>
      </template>
      <p v-if="component.source" class="helper">The corresponding source is included in the APK as <code>assets/public/licenses/{{ component.source.asset }}</code>. <a :href="component.source.url" target="_blank" rel="noopener noreferrer">Upstream source code</a> is also available online.</p>
    </details>
  </details>
</template>
