import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'

const root = resolve(fileURLToPath(new URL('..', import.meta.url)))
const generator = join(root, 'scripts', 'build-hard-manifest.mjs')

function fixture(profileText) {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-hard-manifest-'))
  const archive = join(dir, 'snapshot.tar.xz')
  const output = join(dir, 'manifest.json')
  const py = String.raw`import io,sys,tarfile
texts={
 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-base/cordis.patch.yml': b"- insert:\n  - id: base-entry\n    name: '@vendor/dsh-base'\n",
 'usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/cordis.patch.yml': b"- insert:\n  - id: web-entry\n    name: '@vendor/dsh-web-app'\n",
 'home/.dsh/profiles/web/cordis.patch.yml': sys.argv[2].encode(),
}
with tarfile.open(sys.argv[1],'w:xz') as archive:
  for path,text in texts.items():
    item=tarfile.TarInfo(path)
    item.size=len(text)
    archive.addfile(item,io.BytesIO(text))`
  const made = spawnSync(process.platform === 'win32' ? 'python' : 'python3', ['-c', py, archive, profileText], { encoding: 'utf8' })
  assert.equal(made.status, 0, made.stderr)
  return { dir, archive, output }
}

test('hard manifest binds exact insert id/name identities to final snapshot bytes', () => {
  const f = fixture("- insert:\n  - id: factory-one\n    name: '@vendor/factory'\n    config:\n      name: display-only\n- insert:\n  - id: factory-two\n    name: local-hard\n")
  try {
    const run = spawnSync(process.execPath, [generator, '--snapshot', f.archive, '--out', f.output], { encoding: 'utf8' })
    assert.equal(run.status, 0, run.stderr)
    const manifest = JSON.parse(readFileSync(f.output, 'utf8'))
    assert.equal(manifest.schema, 2)
    assert.equal(manifest.complete, true)
    assert.equal(manifest.fingerprint.length, 64)
    assert.deepEqual(manifest.entries, [
      { id: 'base-entry', name: '@vendor/dsh-base' },
      { id: 'web-entry', name: '@vendor/dsh-web-app' },
      { id: 'factory-one', name: '@vendor/factory' },
      { id: 'factory-two', name: 'local-hard' },
    ])
    assert.deepEqual(manifest.profileEntries, [
      { id: 'factory-one', name: '@vendor/factory' },
      { id: 'factory-two', name: 'local-hard' },
    ])
    assert.deepEqual(manifest.names, ['@vendor/dsh-base', '@vendor/dsh-web-app', '@vendor/factory', 'local-hard'])
  } finally { rmSync(f.dir, { recursive: true, force: true }) }
})

test('hard manifest refuses a product insert that lacks exact id identity', () => {
  const f = fixture("- insert:\n  - name: '@vendor/factory'\n")
  try {
    const run = spawnSync(process.execPath, [generator, '--snapshot', f.archive, '--out', f.output], { encoding: 'utf8' })
    assert.notEqual(run.status, 0)
    assert.match(run.stderr, /no exact id/)
  } finally { rmSync(f.dir, { recursive: true, force: true }) }
})
