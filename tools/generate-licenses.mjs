/**
 * Generate offline notices from production npm packages, build tools that emit
 * runtime helpers, and reviewed Android inputs. No network access; never hand-edit public/licenses.
 * Gradle independently checks these pins against each APK's resolved runtime graph.
 */
import { createHash } from 'node:crypto'
import { readFile, readdir, mkdir, rm, writeFile } from 'node:fs/promises'
import { resolve, basename } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const read = (path) => readFile(resolve(root, path), 'utf8')
const json = async (path) => JSON.parse(await read(path))
const catalog = await json('licenses/catalog.json')
const pkg = await json('package.json')
const lock = await json('package-lock.json')
const helpers = new Set(catalog.npmRuntimeHelpers.map((name) => `node_modules/${name}`))
for (const path of helpers) {
  if (!lock.packages[path]) throw new Error(`Review the missing runtime helper package: ${path}`)
}
const sources = new Map()
for (const [path, metadata] of Object.entries(catalog.sources)) {
  const text = await read(path)
  if (createHash('sha256').update(text).digest('hex') !== metadata.sha256) {
    throw new Error(`License source checksum mismatch: ${path}`)
  }
  sources.set(path, { title: basename(path), text, url: metadata.url })
}
function notice(path) {
  const result = sources.get(path)
  if (!result) throw new Error(`Unreviewed license source: ${path}`)
  return result
}
if (pkg.license !== 'Apache-2.0') throw new Error('The project license must be Apache-2.0.')
const components = [{
  id: 'project', name: 'Fish Audio TTS for Android', version: pkg.version, license: pkg.license,
  packages: [], notices: [notice('LICENSE'), { title: 'NOTICE', text: await read('NOTICE') }],
}]
for (const [path, entry] of Object.entries(lock.packages)) {
  if (!path || (entry.dev && !helpers.has(path))) continue
  let installed
  try { installed = await json(`${path}/package.json`) } catch (error) {
    if (error.code === 'ENOENT' && entry.optional) continue
    throw error
  }
  if (installed.version !== entry.version) throw new Error(`Run npm ci: ${path} does not match the lockfile.`)
  const files = (await readdir(resolve(root, path), { withFileTypes: true }))
    .filter((file) => file.isFile() && /^(licen[cs]e|notice|copying|copyright)([.-]|$)/i.test(file.name))
    .map((file) => file.name).sort()
  if (!files.some((file) => /^licen[cs]e|^copying/i.test(file)) || !installed.license) {
    throw new Error(`Review the missing license for ${installed.name}@${installed.version}.`)
  }
  const notices = await Promise.all(files.map(async (title) => ({ title, text: await read(`${path}/${title}`) })))
  if (notices.some(({ text }) => !text.trim())) throw new Error(`Empty license in ${path}`)
  components.push({
    id: path, name: installed.name, version: installed.version, license: installed.license,
    packages: [`${installed.name}@${installed.version}`], notices,
  })
}
for (const group of catalog.android) {
  components.push({
    id: `android:${group.name}`, name: group.name, license: group.license,
    packages: group.modules, notices: group.files.map(notice),
  })
}
for (const data of catalog.data) {
  components.push({
    id: `data:${data.name}`, name: data.name, version: data.version, license: data.license,
    packages: [], notices: data.files.map(notice),
    source: { asset: basename(data.source), url: notice(data.source).url },
  })
}
const output = resolve(root, 'public/licenses')
await rm(output, { recursive: true, force: true })
await mkdir(output, { recursive: true })
await writeFile(resolve(output, 'notices.json'), JSON.stringify({ androidProjects: catalog.androidProjects, components }))
const plain = components.map((component) => [
  `${component.name}${component.version ? ` ${component.version}` : ''} — ${component.license}`,
  ...component.packages,
  ...component.notices.map(({ title, text, url }) => `${title}${url ? `\nSource: ${url}` : ''}\n\n${text}`),
  component.source ? `Source code: ${component.source.url}\nAlso included as ${component.source.asset} in this directory.` : '',
].filter(Boolean).join('\n\n')).join('\n\n' + '='.repeat(72) + '\n\n')
await writeFile(resolve(output, 'THIRD_PARTY_NOTICES.txt'), plain + '\n')
for (const data of catalog.data) await writeFile(resolve(output, basename(data.source)), notice(data.source).text)
console.log(`Generated offline notices for the project, ${components.length - catalog.android.length - catalog.data.length - 1} npm packages, ${catalog.android.flatMap((group) => group.modules).length} Android modules and ${catalog.data.length} data set.`)
