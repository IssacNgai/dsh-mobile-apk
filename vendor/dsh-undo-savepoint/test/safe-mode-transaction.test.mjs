import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, writeFile, rm, access, symlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'
import { safeModeSet } from '../lib/core.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const FIXTURE = join(HERE, '../../../scripts/patches/tests/fixtures/cordis.patch.yml')
const exists = async (p) => { try { await access(p); return true } catch { return false } }

test('vendor safe mode validates package backup, retains marker on interruption, and restores bytes', async () => {
  const temp = await mkdtemp(join(tmpdir(), 'dsh-safe-core-'))
  const homeDir = join(temp, 'home')
  const profileDir = join(homeDir, 'profiles', 'web')
  const autoDir = join(temp, 'undo', 'auto')
  const manualDir = join(temp, 'undo', 'manual')
  await mkdir(profileDir, { recursive: true })
  const patch = join(profileDir, 'cordis.patch.yml')
  const pkg = join(profileDir, 'package.json')
  const originalPatch = await readFile(FIXTURE)
  const originalPkg = Buffer.from('{\n  "dsh": { "profile": { "bundles": ["missing-safe-mode-test-bundle"] } }\n}\n')
  await writeFile(patch, originalPatch)
  await writeFile(pkg, originalPkg)
  const cfg = { homeDir, profileDir, autoDir, manualDir, profileName: 'web', autoEnabled: false, pluginDirs: [] }
  try {
    const on = await safeModeSet(cfg, true, { async atomicWrite(target, bytes) {
      if (target === pkg) throw new Error('injected interruption')
      const { writeFile, rename, rm } = await import('node:fs/promises')
      const tmp = target + '.test-tmp'
      await writeFile(tmp, bytes)
      await rename(tmp, target)
      await rm(tmp, { force: true })
    } })
    assert.equal(on.ok, false)
    const statePath = join(autoDir, 'safe-mode.json')
    assert.equal(await exists(statePath), true)
    const state = JSON.parse(await readFile(statePath, 'utf8'))
    assert.equal(await exists(state.backup), true)
    assert.equal(await exists(state.pkgBackup), true)
    const refused = await safeModeSet(cfg, false, { async atomicWrite(target, bytes) {
      const { writeFile, rename } = await import('node:fs/promises')
      const tmp = target + '.test-tmp'
      await writeFile(tmp, bytes); await rename(tmp, target)
    } })
    assert.equal(refused.ok, true)
    assert.deepEqual(await readFile(patch), originalPatch)
    assert.deepEqual(await readFile(pkg), originalPkg)
    assert.equal(await exists(statePath), false)
  } finally {
    await rm(temp, { recursive: true, force: true })
  }
})

test('vendor safe mode restores through an autoDir symlink and rejects an out-of-root backup', async () => {
  const temp = await mkdtemp(join(tmpdir(), 'dsh-safe-symlink-'))
  const homeDir = join(temp, 'home')
  const profileDir = join(homeDir, 'profiles', 'web')
  const realAutoDir = join(temp, 'undo-real', 'auto')
  const autoDir = join(temp, 'undo-alias', 'auto')
  const manualDir = join(temp, 'undo-alias', 'manual')
  await mkdir(profileDir, { recursive: true })
  await mkdir(realAutoDir, { recursive: true })
  await mkdir(dirname(autoDir), { recursive: true })
  await symlink(realAutoDir, autoDir, 'dir')
  const patch = join(profileDir, 'cordis.patch.yml')
  const pkg = join(profileDir, 'package.json')
  const originalPatch = await readFile(FIXTURE)
  const originalPkg = Buffer.from('{\n  "dsh": { "profile": { "bundles": ["missing-safe-mode-test-bundle"] } }\n}\n')
  await writeFile(patch, originalPatch)
  await writeFile(pkg, originalPkg)
  const cfg = { homeDir, profileDir, autoDir, manualDir, profileName: 'web', autoEnabled: false, pluginDirs: [] }
  try {
    const on = await safeModeSet(cfg, true, { async atomicWrite(target, bytes) {
      if (target === pkg) throw new Error('injected interruption')
      await writeFile(target, bytes)
    } })
    assert.equal(on.ok, false)
    const statePath = join(autoDir, 'safe-mode.json')
    const state = JSON.parse(await readFile(statePath, 'utf8'))
    assert.equal(dirname(state.backup), autoDir)

    // A symlink alias is accepted for the parent, but the stored filename and
    // real file must remain directly inside the configured auto directory.
    const refusedState = { ...state, backup: join(temp, 'outside', basename(state.backup)) }
    await mkdir(dirname(refusedState.backup), { recursive: true })
    await writeFile(refusedState.backup, originalPatch)
    await writeFile(statePath, JSON.stringify(refusedState))
    const refused = await safeModeSet(cfg, false)
    assert.equal(refused.ok, false)
    assert.equal(await exists(statePath), true)

    await writeFile(statePath, JSON.stringify(state))
    const restored = await safeModeSet(cfg, false)
    assert.equal(restored.ok, true)
    assert.deepEqual(await readFile(patch), originalPatch)
    assert.deepEqual(await readFile(pkg), originalPkg)
    assert.equal(await exists(statePath), false)
  } finally {
    await rm(temp, { recursive: true, force: true })
  }
})
