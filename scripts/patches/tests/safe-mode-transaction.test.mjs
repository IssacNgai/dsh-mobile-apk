import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, existsSync, symlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname, basename } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const FIXTURE = join(HERE, 'fixtures/cordis.patch.yml')

test('emergency CLI keeps recovery marker across interrupted writes and round-trips fixture', async () => {
  const temp = mkdtempSync(join(tmpdir(), 'dsh-safe-cli-'))
  const oldEnv = { DSH_HOME: process.env.DSH_HOME, DSH_UNDO_ROOT: process.env.DSH_UNDO_ROOT, DSH_UNDO_PROFILE: process.env.DSH_UNDO_PROFILE }
  const home = join(temp, 'home')
  const undo = join(temp, 'undo-alias')
  const realUndo = join(temp, 'undo-real')
  const profile = join(home, 'profiles', 'web')
  mkdirSync(profile, { recursive: true })
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
    assert.equal(safeMode('on'), true)
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
  const home = join(temp, 'home')
  const realUndo = join(temp, 'undo-real')
  const undo = join(temp, 'undo-alias')
  const profile = join(home, 'profiles', 'web')
  mkdirSync(profile, { recursive: true })
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

function awaitWrite(target, bytes) { writeFileSync(target, bytes) }
