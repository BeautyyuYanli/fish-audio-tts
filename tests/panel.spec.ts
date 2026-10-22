import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { Configuration, PreviewStatus, VoiceProfile } from '../src/native'

interface Fixture {
  config: Configuration
  calls: { method: string; args: Record<string, unknown> }[]
  failSave: boolean
  failDefault: boolean
  failRemove: boolean
  failLanguage: boolean
  catalogError: boolean
  emptyVoices: boolean
  delay: number
  listener?: (status: PreviewStatus) => void
}
declare global { interface Window { panelFixture: Fixture } }

/** Bridge fixtures verify UI behavior/failure recovery, not Android storage or real Fish access. */
async function loadPanel(page: Page) {
  await page.addInitScript(() => {
    const fixture: Fixture = window.panelFixture = {
      config: { hasApiKey: true, configured: true, defaultVoiceId: 'first', language: 'unset', voices: [
        { id: 'first', label: 'Reading voice', referenceId: 'reference-a', model: '', prefixText: '', parameters: { temperature: 0.4 } },
        { id: 'second', label: 'Narrator clone', referenceId: 'reference-b', model: 'drama-3', prefixText: '', parameters: {} },
      ] },
      calls: [], failSave: false, failDefault: false, failRemove: false, failLanguage: false, catalogError: false, emptyVoices: false, delay: 150,
    }
    let sequence = 0
    const status: PreviewStatus = { state: 'idle', message: 'No preview yet.' }
    const reply = () => {
      if (!fixture.config.voices.some((voice) => voice.id === fixture.config.defaultVoiceId)) fixture.config.defaultVoiceId = fixture.config.voices[0]?.id || ''
      fixture.config.configured = fixture.config.hasApiKey && fixture.config.voices.length > 0
      return structuredClone(fixture.config)
    }
    const methods = ['getConfiguration', 'saveApiKey', 'deleteApiKey', 'saveLanguage', 'saveVoice', 'removeVoice', 'setDefaultVoice', 'getSynthesisModels', 'getOwnVoices', 'getPreviewStatus', 'preview', 'stop', 'openTtsSettings', 'removeListener']
    Object.assign(window, {
      androidBridge: {},
      Capacitor: {
        PluginHeaders: [{ name: 'FishTts', methods: [...methods.map((name) => ({ name, rtype: 'promise' })), { name: 'addListener', rtype: 'callback' }] }],
        nativeCallback: (_plugin: string, method: string, _args: unknown, callback: (value: PreviewStatus) => void) => {
          if (method === 'addListener') fixture.listener = callback
          return 'fixture-listener'
        },
        nativePromise: async (_plugin: string, method: string, args: Record<string, unknown> = {}) => {
          fixture.calls.push({ method, args: method === 'saveApiKey' ? {} : structuredClone(args) })
          if (method === 'getConfiguration') return reply()
          if (method === 'getPreviewStatus') return structuredClone(status)
          const failCatalog = fixture.catalogError
          const empty = fixture.emptyVoices
          await new Promise((resolve) => setTimeout(resolve, fixture.delay))
          if (method === 'saveVoice') {
            if (fixture.failSave) throw new Error('Unable to save this voice. Please try again.')
            const voice = structuredClone(args.voice as VoiceProfile)
            voice.id ||= 'created-' + ++sequence
            const index = fixture.config.voices.findIndex((saved) => saved.id === voice.id)
            if (index < 0) fixture.config.voices.push(voice)
            else fixture.config.voices[index] = voice
            return { ...reply(), savedVoiceId: voice.id }
          }
          if (method === 'removeVoice') {
            if (fixture.failRemove) throw new Error('Unable to remove this voice. Please try again.')
            fixture.config.voices = fixture.config.voices.filter((voice) => voice.id !== args.voiceId)
          }
          if (method === 'setDefaultVoice') {
            if (fixture.failDefault) throw new Error('Unable to save the default voice. Please try again.')
            fixture.config.defaultVoiceId = args.voiceId as string
          }
          if (method === 'saveLanguage') {
            if (fixture.failLanguage) throw new Error('Unable to save the global language. Please try again.')
            fixture.config.language = args.language as Configuration['language']
          }
          if (method === 'saveApiKey') fixture.config.hasApiKey = true
          if (method === 'deleteApiKey') fixture.config.hasApiKey = false
          if (method === 'getSynthesisModels') return { models: ['s1', 's2-pro', 's2.1-pro', 's2.1-pro-free', 'drama-3'] }
          if (method === 'getOwnVoices') {
            if (failCatalog) throw new Error('Unable to load the Fish Audio catalog. Check your network connection.')
            if (empty) return { voices: [], hasMore: false }
            return args.pageNumber === 1
              ? { voices: [{ id: 'owned-reference-a', title: 'My created voice' }, { id: 'R'.repeat(128), title: 'Long cloned voice ' + 'A'.repeat(80) }], hasMore: true }
              : { voices: [{ id: 'owned-reference-b', title: 'My second clone' }], hasMore: false }
          }
          if (method === 'preview' || method === 'stop') {
            status.state = method === 'preview' ? 'synthesizing' : 'stopped'
            status.message = method === 'preview' ? 'Requesting speech from Fish Audio...' : 'Preview stopped.'
            fixture.listener?.(structuredClone(status))
          }
          return reply()
        },
      },
    })
  })
  await page.goto('/')
  await expect(page.locator('#page-status')).toHaveText('Configuration loaded.')
}

async function editor(page: Page, index: number) {
  const row = page.locator('article[data-voice]').nth(index)
  const toggle = row.locator('[data-action=expand-voice]')
  if (await toggle.getAttribute('aria-expanded') !== 'true') await toggle.click()
  return row
}

test('row save validates only that row and preserves another draft through slow and failed writes', async ({ page }) => {
  await loadPanel(page)
  await expect(page.locator('#global-configuration')).not.toHaveAttribute('open')
  const first = await editor(page, 0)
  const second = await editor(page, 1)
  await second.locator('.advanced > summary').click()
  await second.locator('[data-field=parameters]').fill('invalid JSON')
  await first.locator('input[id^=label-]').fill('Updated reading voice')
  await page.evaluate(() => { window.panelFixture.delay = 400 })
  await first.locator('[data-action=save-voice]').click()
  await expect(first.locator('input[id^=label-]')).toBeDisabled()
  await second.locator('input[id^=label-]').fill('Unrelated draft during save')
  await expect(first).toHaveAttribute('data-dirty', 'false')
  await expect(second.locator('[data-field=parameters]')).toHaveValue('invalid JSON')
  await expect(second.locator('input[id^=label-]')).toHaveValue('Unrelated draft during save')
  expect(await page.evaluate(() => window.panelFixture.config.voices[1]?.label)).toBe('Narrator clone')
  await expect(page.locator('#start-preview')).toBeEnabled()
  await page.locator('#preview-voice').selectOption('second')
  await expect(page.locator('#start-preview')).toBeDisabled()
  await second.locator('[data-action=save-voice]').click()
  await expect(second.getByRole('alert')).toContainText('valid JSON object')
  await second.locator('[data-field=parameters]').fill('{"temperature":0.8}')
  await page.evaluate(() => { window.panelFixture.failSave = true })
  await second.locator('[data-action=save-voice]').click()
  await expect(second.getByRole('alert')).toContainText('Unable to save this voice')
  await expect(second.locator('[data-field=parameters]')).toHaveValue('{"temperature":0.8}')
  await page.evaluate(() => { window.panelFixture.failSave = false })
  await second.locator('[data-action=save-voice]').click()
  await expect(second).toHaveAttribute('data-dirty', 'false')
  expect(await page.evaluate(() => window.panelFixture.calls.filter((call) => call.method === 'saveVoice').map((call) => (call.args.voice as VoiceProfile).id))).toEqual(['first', 'second', 'second'])
})

test('default radio rollback and per-row removal preserve unrelated drafts', async ({ page }) => {
  await loadPanel(page)
  const first = await editor(page, 0)
  const second = page.locator('article[data-voice]').nth(1)
  await first.locator('input[id^=label-]').fill('Keep this draft')
  await page.evaluate(() => { window.panelFixture.failDefault = true })
  await second.locator('input[type=radio]').focus()
  await page.keyboard.press('Space')
  await expect(second.getByRole('alert')).toContainText('Unable to save the default')
  await expect(first.locator('input[type=radio]')).toBeChecked()
  await expect(second.locator('input[type=radio]')).not.toBeChecked()
  await expect(second.locator('[data-action=expand-voice]')).toHaveAttribute('aria-expanded', 'false')
  await page.evaluate(() => { window.panelFixture.failDefault = false })
  await second.locator('input[type=radio]').check()
  await expect(second.getByRole('status')).toHaveText('Default system voice saved.')
  await expect(page.locator('#preview-voice')).toHaveValue('first')
  const openSecond = await editor(page, 1)
  await page.evaluate(() => { window.panelFixture.failRemove = true })
  await openSecond.locator('[data-action=remove-voice]').click()
  await expect(openSecond.getByRole('alert')).toContainText('Unable to remove')
  await page.evaluate(() => { window.panelFixture.failRemove = false })
  await openSecond.locator('[data-action=remove-voice]').click()
  await expect(page.locator('article[data-voice]')).toHaveCount(1)
  await expect(first.locator('input[id^=label-]')).toHaveValue('Keep this draft')
  await expect(first.locator('input[type=radio]')).toBeChecked()
})

test('pickers reverse upstream choices across pages, retry, and discard closed/account results', async ({ page }) => {
  await loadPanel(page)
  expect(await page.evaluate(() => window.panelFixture.calls.filter((call) => call.method.startsWith('getOwn') || call.method === 'getSynthesisModels').length)).toBe(0)
  const row = await editor(page, 0)
  const model = row.locator('details[id^=model-]')
  await model.locator('summary').click()
  await expect(model.getByRole('status')).toHaveText('Loading…')
  await expect(model.locator('[data-option-value]')).toHaveCount(6)
  expect(await model.locator('[data-option-value]').evaluateAll((buttons) => buttons.map((button) => button.getAttribute('data-option-value'))))
    .toEqual(['', 'drama-3', 's2.1-pro-free', 's2.1-pro', 's2-pro', 's1'])
  await model.locator('[data-option-value="s1"]').click()
  await expect(model.locator('summary')).toHaveText('s1')
  await expect(row).toHaveAttribute('data-dirty', 'true')
  expect(await page.evaluate(() => window.panelFixture.calls.some((call) => call.method === 'saveVoice'))).toBe(false)
  const own = row.locator('details[id^=own-voice-]')
  await own.locator('summary').click()
  await expect(own.locator('[data-option-value]')).toHaveCount(2)
  expect(await own.locator('[data-option-value]').evaluateAll((buttons) => buttons.map((button) => button.getAttribute('data-option-value'))))
    .toEqual(['R'.repeat(128), 'owned-reference-a'])
  await own.getByRole('button', { name: 'Load more', exact: true }).click()
  await expect(own.locator('[data-option-value]')).toHaveCount(3)
  expect(await own.locator('[data-option-value]').evaluateAll((buttons) => buttons.map((button) => button.getAttribute('data-option-value'))))
    .toEqual(['owned-reference-b', 'R'.repeat(128), 'owned-reference-a'])
  await own.locator('[data-option-value="owned-reference-b"]').click()
  await expect(row.locator('[data-field=referenceId]')).toHaveValue('owned-reference-b')
  await page.evaluate(() => { window.panelFixture.catalogError = true })
  await own.locator('summary').click()
  await expect(own.getByRole('alert')).toContainText('Check your network connection')
  await page.evaluate(() => { window.panelFixture.catalogError = false; window.panelFixture.emptyVoices = true })
  await own.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(own).toContainText('No voices found in the current workspace.')
  await own.locator('summary').click()
  await page.evaluate(() => { window.panelFixture.emptyVoices = false; window.panelFixture.delay = 400 })
  await own.locator('summary').click()
  await own.locator('summary').click()
  await page.waitForTimeout(450)
  await expect(own.locator('[data-option-value]')).toHaveCount(0)
  await own.locator('summary').click()
  await page.locator('#global-configuration > summary').click()
  await page.locator('#api-key').fill('fixture-not-an-api-credential')
  await page.locator('#save-api-key').click()
  await expect(page.getByText('API key saved in protected native storage.', { exact: true })).toBeVisible()
  await expect(own).not.toHaveAttribute('open')
  await expect(own.locator('[data-option-value]')).toHaveCount(0)
  await expect(row.locator('[data-field=referenceId]')).toHaveValue('owned-reference-b')
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
})

test('global language saves independently of drafts and rolls back failed changes', async ({ page }) => {
  await loadPanel(page)
  const row = await editor(page, 0)
  await row.locator('input[id^=label-]').fill('Keep this voice draft')
  const global = page.locator('#global-configuration')
  await global.locator(':scope > summary').click()
  await page.locator('#api-key').fill('unsaved-test-key')
  const languageSwitch = page.getByRole('switch', { name: 'Use system language' })
  await expect(languageSwitch).not.toBeChecked()
  await expect(page.locator('#preview-language')).toBeDisabled()
  await expect(page.locator('#global-language-help')).toContainText('Off (unset)')
  await page.evaluate(() => { window.panelFixture.failLanguage = true })
  await languageSwitch.check()
  await expect(global.getByRole('alert')).toContainText('Unable to save the global language')
  await expect(languageSwitch).not.toBeChecked()
  await expect(page.locator('#preview-language')).toBeDisabled()
  expect(await page.evaluate(() => window.panelFixture.config.language)).toBe('unset')
  await page.evaluate(() => { window.panelFixture.failLanguage = false; window.panelFixture.delay = 400 })
  await languageSwitch.check()
  await expect(languageSwitch).toBeDisabled()
  await expect(global.getByRole('status')).toHaveText('Global language saved.')
  expect(await page.evaluate(() => window.panelFixture.config.language)).toBe('system')
  await expect(page.locator('#preview-language')).toBeEnabled()
  await page.locator('#preview-language').selectOption('ja-JP')
  await expect(page.locator('#api-key')).toHaveValue('unsaved-test-key')
  await expect(row.locator('input[id^=label-]')).toHaveValue('Keep this voice draft')
  expect(await page.evaluate(() => window.panelFixture.config.voices[0]?.label)).toBe('Reading voice')
  await page.locator('#save-api-key').click()
  await expect(global).toContainText('API key saved in protected native storage.')
  await expect(languageSwitch).toBeChecked()
  await languageSwitch.focus()
  await page.keyboard.press('Space')
  await expect(global.getByText('Global language saved.', { exact: true })).toBeVisible()
  await expect(languageSwitch).not.toBeChecked()
  await expect(page.locator('#preview-language')).toBeDisabled()
  await expect(page.locator('#preview-language')).toHaveValue('ja-JP')
  expect(await page.evaluate(() => window.panelFixture.config.language)).toBe('unset')
})

test('active preview disables Start and editing while allowing Stop', async ({ page }) => {
  await loadPanel(page)
  await page.locator('#start-preview').click()
  await expect(page.locator('#stop-preview')).toBeEnabled()
  await expect(page.locator('#start-preview')).toBeDisabled()
  await expect(page.locator('#preview-text')).toBeDisabled()
  await expect(page.locator('#preview-pitch')).toBeDisabled()
  await expect(page.locator('#global-language')).toBeDisabled()
  await page.locator('#stop-preview').click()
  await expect(page.locator('#preview-text')).toBeEnabled()
  await expect(page.locator('#stop-preview')).toBeDisabled()
})

test('bundled licenses remain on the page, include full texts, and preserve voice drafts', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 })
  await loadPanel(page)
  const row = await editor(page, 0)
  await row.locator('input[id^=label-]').fill('Keep my unsaved voice')
  const requests: string[] = []
  page.on('request', (request) => requests.push(request.url()))
  const licenses = page.locator('#open-source-licenses')
  await expect(licenses).not.toHaveAttribute('open')
  await licenses.locator(':scope > summary').click()
  const vue = licenses.locator('.license-component').filter({ has: page.locator('summary', { hasText: /^vue / }) })
  await vue.locator('summary').click()
  await expect(vue).toContainText('Yuxi (Evan) You')
  await expect(vue).toContainText('Permission is hereby granted, free of charge')
  await expect(vue).toContainText('THE SOFTWARE IS PROVIDED "AS IS"')
  const vite = licenses.locator('.license-component').filter({ has: page.locator('summary', { hasText: /^vite / }) })
  await vite.locator('summary').click()
  await expect(vite).toContainText('VoidZero Inc. and Vite contributors')
  const cordova = licenses.locator('.license-component').filter({ has: page.locator('summary', { hasText: /^Apache Cordova / }) })
  await cordova.locator('summary').click()
  await expect(cordova).toContainText('The Apache Software Foundation')
  const psl = licenses.locator('.license-component').filter({ has: page.locator('summary', { hasText: /^Public Suffix List / }) })
  await psl.locator('summary').click()
  await expect(psl).toContainText('Mozilla Public License Version 2.0')
  await expect(psl).toContainText('public_suffix_list.dat')
  expect(requests).toHaveLength(1)
  expect(new URL(requests[0]!).pathname).toBe('/licenses/notices.json')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  await licenses.locator(':scope > summary').click()
  await expect(row.locator('input[id^=label-]')).toHaveValue('Keep my unsaved voice')
  await expect(row).toHaveAttribute('data-dirty', 'true')
  expect(await page.evaluate(() => window.panelFixture.calls.some((call) => call.method === 'saveVoice'))).toBe(false)
})

test('licenses can retry a failed asset load without touching configuration', async ({ page }) => {
  await loadPanel(page)
  await page.route('**/licenses/notices.json', (route) => route.fulfill({ status: 503, body: '' }))
  const licenses = page.locator('#open-source-licenses')
  await licenses.locator(':scope > summary').click()
  await expect(licenses.getByRole('alert')).toContainText('Unable to load the bundled licenses')
  await page.unroute('**/licenses/notices.json')
  await licenses.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(licenses.getByRole('alert')).toHaveCount(0)
  await expect(licenses.locator('.license-component').first().locator('summary')).toContainText('Fish Audio TTS for Android')
})

for (const width of [320, 360, 390, 412, 1280]) {
  test('layout and full remote names at ' + width + 'px', async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 844 })
    const runtimeErrors: string[] = []
    page.on('pageerror', (error) => runtimeErrors.push(error.message))
    await loadPanel(page)
    await page.locator('#global-configuration > summary').click()
    await page.screenshot({ path: testInfo.outputPath('overview.png'), fullPage: true })
    const row = await editor(page, 0)
    await row.locator('input[id^=label-]').fill('A'.repeat(100))
    const own = row.locator('details[id^=own-voice-]')
    await own.locator('summary').click()
    await expect(own.locator('.option-id')).toHaveCount(2)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
    expect(await row.locator('.default-choice').evaluate((element) => { const rect = element.getBoundingClientRect(); return rect.width >= 48 && rect.height >= 48 })).toBe(true)
    expect(await row.locator('[data-action=expand-voice]').evaluate((element) => element.getBoundingClientRect().height >= 48)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('open-picker.png'), fullPage: true })
    await own.locator('summary').click()
    await page.locator('#add-voice').click()
    await page.locator('#add-voice').click()
    const newRadios = page.locator('article[data-voice] input[type=radio]').filter({ visible: true })
    for (const index of [2, 3]) { await expect(newRadios.nth(index)).toBeDisabled(); await expect(newRadios.nth(index)).not.toBeChecked() }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
    expect(runtimeErrors).toEqual([])
  })
}
