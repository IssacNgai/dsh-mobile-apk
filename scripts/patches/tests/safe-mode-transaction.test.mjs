import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, existsSync, symlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname, basename } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const FIXTURE = join(HERE, 'fixtures/safe-mode-policy.yml')
const EXPECTED = join(HERE, 'fixtures/safe-mode-policy.expected.yml')

test('emergency CLI keeps recovery marker across interrupted writes and round-trips fixture', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const filesDir = join(temp, 'files')
  const home = join(filesDir, 'home', '.dsh')
  const undo = join(temp, 'undo-alias')
  const realUndo = join(temp, 'undo-real')
  const profile = join(home, 'profiles', 'web')
  mkdirSync(profile, { recursive: true })
  writeOwnershipManifest(filesDir)
  const original = readFileSync(FIXTURE)
  const patch = join(profile, 'cordis.patch.yml')
  writeFileSync(patch, original)
  process.env.DSH_HOME = home
  mkdirSync(join(realUndo, 'web', 'auto'), { recursive: true })
  symlinkSync(realUndo, undo, 'dir')
  process.env.DSH_UNDO_ROOT = undo
  process.env.DSH_UNDO_PROFILE = 'web'
  try {
    const { safeMode } = await import(pathToFileURL(join(HERE, '../../dsh-undo-emergency.mjs')).href + `?test=${Math.random()}`)
    const ok = safeMode('on', { atomicWrite(target, bytes) {
      if (target === patch) throw new Error('injected interruption')
      return (awaitWrite(target, bytes))
    } })
    assert.equal(ok, false)
    const state = join(undo, 'web', 'auto', 'safe-mode.json')
    assert.equal(existsSync(state), true)
    assert.equal(readFileSync(patch).equals(original), true)
    assert.equal(safeMode('off'), true)
    assert.equal(safeMode('on'), true)
    assert.equal(readFileSync(patch, 'utf8'), readFileSync(EXPECTED, 'utf8'))
    assert.equal(safeMode('off'), true)
    assert.equal(readFileSync(patch).equals(original), true)
    assert.equal(existsSync(state), false)
    assert.equal(safeMode('off'), true)
  } finally {
    for (const [key, value] of Object.entries(oldEnv)) value === undefined ? delete process.env[key] : process.env[key] = value
    rmSync(temp, { recursive: true, force: true })
  }
})

test('emergency CLI accepts autoDir symlink aliases and rejects backup paths outside autoDir', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-boundary-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const filesDir = join(temp, 'files')
  const home = join(filesDir, 'home', '.dsh')
  const realUndo = join(temp, 'undo-real')
  const undo = join(temp, 'undo-alias')
  const profile = join(home, 'profiles', 'web')
  mkdirSync(profile, { recursive: true })
  mkdirSync(filesDir, { recursive: true })
  writeOwnershipManifest(filesDir)
  mkdirSync(join(realUndo, 'web', 'auto'), { recursive: true })
  symlinkSync(realUndo, undo, 'dir')
  const patch = join(profile, 'cordis.patch.yml')
  const original = readFileSync(FIXTURE)
  writeFileSync(patch, original)
  process.env.DSH_HOME = home
  process.env.DSH_UNDO_ROOT = undo
  process.env.DSH_UNDO_PROFILE = 'web'
  try {
    const { safeMode } = await import(pathToFileURL(join(HERE, '../../dsh-undo-emergency.mjs')).href + `?boundary=${Math.random()}`)
    assert.equal(safeMode('on'), true)
    const statePath = join(undo, 'web', 'auto', 'safe-mode.json')
    const state = JSON.parse(readFileSync(statePath, 'utf8'))
    const outside = join(temp, 'outside', basename(state.backup))
    mkdirSync(dirname(outside), { recursive: true })
    writeFileSync(outside, original)
    writeFileSync(statePath, JSON.stringify({ ...state, backup: outside }))
    assert.equal(safeMode('off'), false)
    assert.equal(existsSync(statePath), true)
    writeFileSync(statePath, JSON.stringify(state))
    assert.equal(safeMode('off'), true)
    assert.equal(readFileSync(patch).equals(original), true)
    assert.equal(existsSync(statePath), false)
  } finally {
    for (const [key, value] of Object.entries(oldEnv)) value === undefined ? delete process.env[key] : process.env[key] = value
    rmSync(temp, { recursive: true, force: true })
  }
})

test('emergency CLI refuses Safe Mode when the exact ownership manifest is absent', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-no-manifest-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const home = join(temp, 'files', 'home', '.dsh')
  const profile = join(home, 'profiles', 'web')
  mkdirSync(profile, { recursive: true })
  const original = readFileSync(FIXTURE)
  const patch = join(profile, 'cordis.patch.yml')
  writeFileSync(patch, original)
  process.env.DSH_HOME = home
  process.env.DSH_UNDO_ROOT = join(temp, 'undo')
  process.env.DSH_UNDO_PROFILE = 'web'
  try {
    const { safeMode } = await import(pathToFileURL(join(HERE, '../../dsh-undo-emergency.mjs')).href + `?no-manifest=${Math.random()}`)
    assert.equal(safeMode('on'), false)
    assert.equal(readFileSync(patch).equals(original), true)
    assert.equal(existsSync(join(temp, 'undo', 'web', 'auto', 'safe-mode.json')), false)
  } finally {
    for (const [key, value] of Object.entries(oldEnv)) value === undefined ? delete process.env[key] : process.env[key] = value
    rmSync(temp, { recursive: true, force: true })
  }
})

test('emergency CLI selects the transaction sidecar during online probation', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-online-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const filesDir = join(temp, 'files')
  const home = join(filesDir, 'home', '.dsh')
  const profileDir = join(home, 'profiles', 'web')
  const autoDir = join(home, 'undo-snapshots', 'auto')
  mkdirSync(profileDir, { recursive: true })
  writeOwnershipManifest(filesDir)
  const base = 'a'.repeat(64), archive = 'b'.repeat(64)
  const cachePath = join(filesDir, '.plugin-hard-manifest.json')
  const cache = JSON.parse(readFileSync(cachePath, 'utf8'))
  const onlineHard = { id: 'online-hard', name: '@vendor/online-hard' }
  writeFileSync(join(filesDir, `.plugin-hard-manifest-online-${archive}.json`), JSON.stringify({
    schema: 2, complete: true, fingerprint: archive, baseFingerprint: base,
    entries: [...cache.entries, onlineHard], profileEntries: cache.profileEntries,
  }))
  writeFileSync(join(filesDir, '.snapshot-transaction'), [
    'phase=SWAPPED', 'purpose=ONLINE_UPDATE', `fingerprint=${archive}`,
    `baseFingerprint=${base}`, `priorFingerprint=${base}`,
  ].join('\n'))
  const patch = join(profileDir, 'cordis.patch.yml')
  writeFileSync(patch, readFileSync(FIXTURE, 'utf8') + `- insert:\n  - id: online-hard\n    name: '@vendor/online-hard'\n`)
  process.env.DSH_HOME = home
  process.env.DSH_UNDO_ROOT = join(temp, 'undo')
  process.env.DSH_UNDO_PROFILE = 'web'
  try {
    const { safeMode } = await import(pathToFileURL(join(HERE, '../../dsh-undo-emergency.mjs')).href + `?online=${Math.random()}`)
    assert.equal(safeMode('on'), true)
    assert.match(readFileSync(patch, 'utf8'), /id: online-hard/)
    assert.equal(safeMode('off'), true)
    writeFileSync(join(filesDir, '.snapshot-transaction'), `phase=ONLINE_COMMITTED\npurpose=FACTORY\nfingerprint=${archive}\n`)
    const beforeUnknownMarker = readFileSync(patch)
    assert.equal(safeMode('on'), false, 'CLI must reject an ONLINE_COMMITTED marker with incompatible purpose')
    assert.equal(readFileSync(patch).equals(beforeUnknownMarker), true, 'unknown marker must not mutate user data')
  } finally {
    for (const [key, value] of Object.entries(oldEnv)) value === undefined ? delete process.env[key] : process.env[key] = value
    rmSync(temp, { recursive: true, force: true })
  }
})

function awaitWrite(target, bytes) { writeFileSync(target, bytes) }

function writeOwnershipManifest(filesDir) {
  const fingerprint = 'a'.repeat(64)
  mkdirSync(filesDir, { recursive: true })
  writeFileSync(join(filesDir, '.snapshot-fingerprint'), fingerprint)
  writeFileSync(join(filesDir, '.plugin-hard-manifest.json'), JSON.stringify({
    schema: 2, complete: true, fingerprint,
    entries: [
      { id: 'mobile-hard', name: '@dsh-android/dsh-shell-termux' },
      { id: 'upstream-hard', name: '@deepseek-ai/dsh-shipped-core' },
      { id: 'undo', name: 'dsh-undo-savepoint' },
      { id: 'disabled-product', name: '@dsh-android/dsh-host-web-compat' },
    ],
    profileEntries: [
      { id: 'mobile-hard', name: '@dsh-android/dsh-shell-termux' },
      { id: 'disabled-product', name: '@dsh-android/dsh-host-web-compat' },
    ],
  }))
}
