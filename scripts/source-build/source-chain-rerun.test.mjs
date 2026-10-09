#!/usr/bin/env node
// Run actual workflow guards against dummy local inputs, never upstream builds.
import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { spawnSync, execFileSync } from 'node:child_process'
import { chmodSync, copyFileSync, cpSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, renameSync, rmSync, writeFileSync, symlinkSync } from 'node:fs'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { dirname, join, relative, resolve } from 'node:path'
const ROOT = resolve(import.meta.dirname, '../..')
const yaml = readFileSync(join(ROOT, '.github/workflows/build-apk-source.yml'), 'utf8').replaceAll('\r\n', '\n')
function step(name, workflow = yaml) {
  const start = workflow.indexOf('      - name: ' + name + '\n')
  assert.notEqual(start, -1)
  const end = workflow.indexOf('\n      - name:', start + 1)
  const block = workflow.slice(start, end < 0 ? undefined : end)
  const marker = '        run: |\n'
  assert.ok(block.includes(marker))
  return block.slice(block.indexOf(marker) + marker.length).split('\n').map(line => line.startsWith('          ') ? line.slice(10) : line).join('\n')
}
// Android's packaged Node reports linker64 as execPath; use its actual executable for fixtures.
const android = process.platform === 'android'
const BASH = android ? join(process.env.PREFIX, 'bin/bash') : '/bin/bash'
const NODE = android ? join(process.env.PREFIX, 'bin/node') : process.execPath
const PYTHON = android ? join(process.env.PREFIX, 'bin/python3') : '/usr/bin/python3'
// setup-node lives outside /usr/bin on CI: retain the exact running Node, not arbitrary host PATH.
const HOST_PATH = android ? process.env.PATH : dirname(NODE) + ':/usr/bin:/bin'
function environment(root, extra = {}) {
  return { PATH: join(root, 'bin') + ':' + HOST_PATH, HOME: root, TMPDIR: root, LC_ALL: 'C',
    ...(android ? Object.fromEntries(Object.entries(process.env).filter(([key]) => key.startsWith('LD_') || key.startsWith('TERMUX_'))) : {}),
    GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: '/dev/null', GIT_ALLOW_PROTOCOL: 'file',
    GITHUB_WORKSPACE: root, ...extra }
}
function fixture(fn) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'source-rerun-')))
  try { return fn(root) } finally { rmSync(root, { recursive: true, force: true }) }
}
function bash(script, root, extra = {}) {
  const r = spawnSync(BASH, ['--noprofile', '--norc', '-c', 'set -euo pipefail\n' + script], {
    cwd: root, encoding: 'utf8', timeout: 10000, maxBuffer: 262144,
    env: environment(root, extra),
  })
  if (r.error) throw r.error
  return r
}
const linux = { skip: !['linux', 'android'].includes(process.platform) }
const buildYaml = readFileSync(join(ROOT, '.github/workflows/build-apk.yml'), 'utf8').replaceAll('\r\n', '\n')
const pluginDirs = JSON.parse(readFileSync(join(ROOT, 'scripts/plugin-dirs.json'), 'utf8')).dirs
const buildableDirs = pluginDirs.filter(d => JSON.parse(readFileSync(join(ROOT, d, 'package.json'), 'utf8')).scripts?.build)
function preparePluginBuild(root) {
  mkdirSync(join(root, 'scripts'), { recursive: true })
  mkdirSync(join(root, 'bin'))
  writeFileSync(join(root, 'scripts/plugin-dirs.json'), JSON.stringify({ dirs: pluginDirs }))
  for (const d of pluginDirs) {
    mkdirSync(join(root, d), { recursive: true })
    writeFileSync(join(root, d, 'package.json'), JSON.stringify({ scripts: buildableDirs.includes(d) ? { build: 'fixture' } : {} }))
  }
  const quote = value => "'" + value.replaceAll("'", "'\\''") + "'"
  writeFileSync(join(root, 'bin/node'), [
    '#!' + BASH, 'set -eu',
    'if [ "$1" = -e ]; then exec ' + quote(NODE) + ' "$@"; fi',
    'printf "deps:%s\\n" "$2" >> "$HOME/plugin-calls"',
    'if [ "$2" = "${FAILURE_PACKAGE:-}" ]; then exit 23; fi', '',
  ].join('\n'))
  writeFileSync(join(root, 'bin/npm'), [
    '#!' + BASH, 'set -eu', 'test "$1" = run; test "$2" = build',
    'printf "build:%s\\n" "${PWD#"$HOME/"}" >> "$HOME/plugin-calls"', '',
  ].join('\n'))
  chmodSync(join(root, 'bin/node'), 0o755)
  chmodSync(join(root, 'bin/npm'), 0o755)
}
for (const [label, script] of [
  ['normal', step('准备并构建插件（统一清单、锁解析与单次构建）', buildYaml)],
  ['source', step('Build project plugins from source')],
]) {
  test(label + ' plugin preparation: manifest order, one install and build per package', linux, () => fixture(root => {
    preparePluginBuild(root)
    const r = bash(script, root)
    assert.equal(r.status, 0, r.stderr)
    assert.deepEqual(readFileSync(join(root, 'plugin-calls'), 'utf8').trim().split('\n'),
      buildableDirs.flatMap(d => ['deps:' + d, 'build:' + d]))
    assert.ok(buildableDirs.indexOf('dsh-shell-termux') < buildableDirs.indexOf('plugins/dsh-android-linux-env'))
  }))
  test(label + ' plugin preparation: dependency failure stops before any package build', linux, () => fixture(root => {
    preparePluginBuild(root)
    const r = bash(script, root, { FAILURE_PACKAGE: buildableDirs[0] })
    assert.equal(r.status, 23, r.stderr)
    assert.equal(readFileSync(join(root, 'plugin-calls'), 'utf8'), 'deps:' + buildableDirs[0] + '\n')
  }))
  test(label + ' plugin preparation: broken manifest cannot silently produce an empty success', linux, () => fixture(root => {
    preparePluginBuild(root)
    writeFileSync(join(root, 'scripts/plugin-dirs.json'), '{broken')
    assert.notEqual(bash(script, root).status, 0)
    assert.equal(existsSync(join(root, 'plugin-calls')), false)
  }))
}
const bootstrap = step('Authenticate official Termux bootstrap and extract signing keys')
const guard = bootstrap.slice(bootstrap.indexOf('bootstrap_sha256='), bootstrap.indexOf('\n# 先清空'))
const good = Buffer.from('authenticated dummy bootstrap\n')
const sha = createHash('sha256').update(good).digest('hex')
function prepare(root) {
  mkdirSync(join(root, 'scripts/source-build'), { recursive: true })
  mkdirSync(join(root, '.deploy-tmp/source-build'), { recursive: true })
  mkdirSync(join(root, 'bin'))
  writeFileSync(join(root, 'scripts/source-build/prepare-termux-bootstrap.py'), 'BOOTSTRAP_SHA256 = "' + sha + '"\n')
  writeFileSync(join(root, 'download'), good)
  const curl = [
    '#!' + BASH, 'set -eu', 'printf "download\\n" >> "$HOME/calls"',
    'while [ "$#" -gt 0 ]; do if [ "$1" = "--output" ]; then output="$2"; break; fi; shift; done',
    'if [ "' + '$' + '{INTERRUPT:-0}" = 1 ] && [ ! -f "$HOME/interrupted" ]; then printf partial > "$output"; touch "$HOME/interrupted"; exit 22; fi',
    'cp "$HOME/download" "$output"', '',
  ].join('\n')
  writeFileSync(join(root, 'bin/curl'), curl)
  chmodSync(join(root, 'bin/curl'), 0o755)
  return join(root, '.deploy-tmp/source-build/bootstrap-aarch64.zip')
}
for (const cached of ['valid', 'absent', 'wrong', 'truncated']) {
  test('bootstrap ' + cached + ': only authenticated hits reuse', linux, () => fixture(root => {
    const cache = prepare(root)
    if (cached !== 'absent') writeFileSync(cache, cached === 'valid' ? good : Buffer.from(cached))
    const r = bash(guard, root)
    assert.equal(r.status, 0, r.stderr)
    assert.deepEqual(readFileSync(cache), good)
    assert.equal(existsSync(join(root, 'calls')), cached !== 'valid')
    assert.ok(bootstrap.includes('python3 scripts/source-build/prepare-termux-bootstrap.py'))
  }))
}
test('interrupted bootstrap download retries next invocation', linux, () => fixture(root => {
  const cache = prepare(root)
  assert.equal(bash(guard, root, { INTERRUPT: '1' }).status, 22)
  assert.notDeepEqual(readFileSync(cache), good)
  const r = bash(guard, root, { INTERRUPT: '1' })
  assert.equal(r.status, 0, r.stderr)
  assert.deepEqual(readFileSync(cache), good)
  assert.equal(readFileSync(join(root, 'calls'), 'utf8').trim().split('\n').length, 2)
}))
test('bad downloaded bytes are rejected before bootstrap extraction', linux, () => fixture(root => {
  const cache = prepare(root)
  writeFileSync(join(root, 'download'), 'bad download')
  assert.equal(bash(guard, root).status, 0)
  const destination = join(root, 'extracted/usr')
  const checked = spawnSync(PYTHON, [join(ROOT, 'scripts/source-build/prepare-termux-bootstrap.py'),
    '--extract-bootstrap-only', cache, destination], {
    encoding: 'utf8', timeout: 10000, env: environment(root),
  })
  if (checked.error) throw checked.error
  assert.notEqual(checked.status, 0)
  assert.ok((checked.stderr + checked.stdout).includes('SHA-256 mismatch'))
  assert.equal(existsSync(destination), false)
}))
const PIN = '639ed015397290b3745d163aafe02ffee4aa3f84'
const TAG = 'dsh-v0.2.0-rc.2'
const checkout = step('Checkout pinned DeepSeek Harness source')
const harness = step('Build and pack DeepSeek Harness from source')
const buildEntry = harness.slice(0, harness.indexOf('\nmkdir -p "$GITHUB_WORKSPACE/.deploy-tmp/source-build"'))
assert.ok(buildEntry.includes('# END pinned Harness reset.'))
function marked(script, name) {
  const start = script.indexOf('# BEGIN ' + name)
  const end = script.indexOf('# END ' + name, start)
  assert.ok(start >= 0 && end > start, name + ' markers missing')
  return script.slice(start, end)
}
test('both workflow entries retain identical scope/reset blocks and immutable identity', () => {
  for (const name of ['dedicated Harness checkout validation', 'pinned Harness reset']) {
    assert.equal(marked(checkout, name), marked(buildEntry, name))
  }
  for (const script of [checkout, buildEntry]) {
    assert.ok(script.includes('checkout --force --detach ' + PIN))
    assert.ok(script.includes('clean -fdq'))
    assert.ok(script.includes('rev-parse HEAD)" = "' + PIN + '"'))
    assert.ok(script.includes('describe --tags --always)" = "' + TAG + '"'))
    assert.equal(script.includes('checkout -- .'), false)
    assert.equal(script.includes('|| true'), false)
  }
  assert.ok(checkout.includes('fetch --depth=1 origin ' + PIN))
  assert.ok(checkout.includes('fetch --depth=1 origin refs/tags/' + TAG + ':refs/tags/' + TAG))
  assert.ok(checkout.includes('c.upstream.commit!=="' + PIN + '"'))
  assert.ok(checkout.includes('p.version!=="0.2.0-rc.2"'))
  assert.ok(checkout.includes('p.packageManager!=="pnpm@11.7.0"'))
  assert.ok(checkout.includes('p.engines.node!=="^22.19.0 || >=24.0.0"'))
})
function git(root, directory, ...args) {
  return execFileSync('git', ['-C', directory, ...args], { encoding: 'utf8', env: environment(root), stdio: 'pipe' }).trim()
}
function localSource(root, state = 'fresh') {
  if (android) {
    // The packaged git-upload-pack shim loses its argv[0] and invokes bare `git.real`.
    // Supply fixture-only dispatch to the same real Git; never modify installed helpers.
    const bin = join(root, 'bin')
    const helpers = join(root, 'git-core')
    mkdirSync(bin)
    mkdirSync(helpers)
    const realGit = join(process.env.PREFIX, 'bin/git.real')
    writeFileSync(join(bin, 'git'), `#!${BASH}\nexport GIT_EXEC_PATH="${helpers}"\nexec "${realGit}" "$@"\n`)
    writeFileSync(join(helpers, 'git-upload-pack'), `#!${BASH}\nexec "${realGit}" upload-pack "$@"\n`)
    chmodSync(join(bin, 'git'), 0o755)
    chmodSync(join(helpers, 'git-upload-pack'), 0o755)
    symlinkSync(join(bin, 'git'), join(helpers, 'git'))
  }
  const upstream = join(root, 'upstream')
  const source = join(root, '.deploy-tmp/deepseek-harness')
  mkdirSync(upstream)
  git(root, upstream, 'init', '-qb', 'fixture')
  git(root, upstream, 'config', 'user.name', 'Fixture')
  git(root, upstream, 'config', 'user.email', 'fixture@example.invalid')
  writeFileSync(join(upstream, '.gitignore'), 'node_modules/\nlib/\n*.tsbuildinfo\n')
  writeFileSync(join(upstream, 'source.txt'), 'pinned')
  writeFileSync(join(upstream, 'delete.txt'), 'restore')
  writeFileSync(join(upstream, 'collision.txt'), 'tracked pin')
  writeFileSync(join(upstream, 'package.json'), JSON.stringify({ version: '0.2.0-rc.2', packageManager: 'pnpm@11.7.0', engines: { node: '^22.19.0 || >=24.0.0' } }))
  git(root, upstream, 'add', '.')
  git(root, upstream, 'commit', '-qm', 'pinned source')
  const pin = git(root, upstream, 'rev-parse', 'HEAD')
  git(root, upstream, 'tag', TAG)
  writeFileSync(join(upstream, 'source.txt'), 'later source')
  rmSync(join(upstream, 'collision.txt')) // Verified fixture target, for an untracked checkout obstruction.
  git(root, upstream, 'add', '-A')
  git(root, upstream, 'commit', '-qm', 'later source')
  mkdirSync(join(root, 'scripts'))
  writeFileSync(join(root, 'scripts/contract.json'), JSON.stringify({ upstreamRepo: '.deploy-tmp/deepseek-harness', baseline: '0.2.0-rc.2', upstream: { commit: pin } }))
  mkdirSync(join(root, '.deploy-tmp'))
  writeFileSync(join(root, '.deploy-tmp/sibling'), 'keep sibling')
  if (state === 'unborn') {
    mkdirSync(source)
    git(root, source, 'init', '-qb', 'unborn')
    git(root, source, 'remote', 'add', 'origin', upstream)
    git(root, source, 'fetch', '-q', 'origin', 'refs/tags/' + TAG + ':refs/tags/' + TAG)
  } else if (state !== 'absent') {
    git(root, root, 'clone', '-q', '--no-checkout', upstream, source)
    if (state !== 'fresh') git(root, source, 'checkout', '-q', '--detach', 'HEAD')
  }
  // Only fixture inputs change: every command/guard comes from the actual workflow.
  // File-only protocol also fails closed if a production URL ever escapes this substitution.
  const script = raw => raw.replaceAll(PIN, pin).replaceAll('https://github.com/deepseek-ai/deepseek-harness.git', upstream)
  return { source, upstream, pin, script }
}
function ignoredCache(source) {
  mkdirSync(join(source, 'node_modules'), { recursive: true })
  writeFileSync(join(source, 'node_modules/sentinel'), 'keep dependency')
  mkdirSync(join(source, 'lib'), { recursive: true })
  writeFileSync(join(source, 'lib/sentinel'), 'keep build cache')
}
function assertPinned(root, f) {
  assert.equal(git(root, f.source, 'rev-parse', 'HEAD'), f.pin)
  assert.equal(git(root, f.source, 'describe', '--tags', '--always'), TAG)
  assert.equal(readFileSync(join(f.source, 'source.txt'), 'utf8'), 'pinned')
  assert.equal(readFileSync(join(f.source, 'delete.txt'), 'utf8'), 'restore')
  assert.equal(readFileSync(join(f.source, 'collision.txt'), 'utf8'), 'tracked pin')
  assert.equal(git(root, f.source, 'status', '--porcelain'), '')
  assert.equal(readFileSync(join(root, '.deploy-tmp/sibling'), 'utf8'), 'keep sibling')
}
test('real --no-checkout clone reproduces the original empty-index failure', linux, () => fixture(root => {
  const f = localSource(root)
  assert.equal(git(root, f.source, 'ls-files'), '')
  const old = bash('git -C .deploy-tmp/deepseek-harness checkout -- .', root)
  assert.notEqual(old.status, 0)
  assert.match(old.stderr, /pathspec '\.' did not match/)
  const r = bash(f.script(checkout), root)
  assert.equal(r.status, 0, r.stderr)
  assertPinned(root, f)
}))
for (const [entry, raw] of [['checkout', checkout], ['build entry', buildEntry]]) {
  for (const state of ['fresh', 'empty index', 'unborn', 'interrupted', 'conflicted']) {
    test(entry + ': real Git ' + state + ' resets exactly to pin and reruns cleanly', linux, () => fixture(root => {
      const f = localSource(root, state === 'empty index' || state === 'interrupted' || state === 'conflicted' ? 'existing' : state)
      if (state === 'empty index') git(root, f.source, 'read-tree', '--empty')
      if (state === 'interrupted') {
        writeFileSync(join(f.source, 'source.txt'), 'staged override')
        writeFileSync(join(f.source, 'added.txt'), 'staged addition')
        git(root, f.source, 'add', '.')
        git(root, f.source, 'rm', '-q', 'delete.txt')
        writeFileSync(join(f.source, 'source.txt'), 'unstaged override after staging')
        mkdirSync(join(f.source, 'untracked'))
        writeFileSync(join(f.source, 'untracked/remove.txt'), 'remove')
        writeFileSync(join(f.source, 'collision.txt'), 'untracked pin obstruction')
      }
      if (state === 'conflicted') {
        const blob = git(root, f.source, 'rev-parse', f.pin + ':source.txt')
        const r = spawnSync('git', ['-C', f.source, 'update-index', '--index-info'], {
          input: `0 ${'0'.repeat(40)}\tsource.txt\n100644 ${blob} 1\tsource.txt\n100644 ${blob} 2\tsource.txt\n100644 ${blob} 3\tsource.txt\n`,
          env: environment(root), encoding: 'utf8',
        })
        if (r.error) throw r.error
        assert.equal(r.status, 0, r.stderr)
        assert.ok(git(root, f.source, 'ls-files', '-u'))
      }
      ignoredCache(f.source)
      for (let run = 0; run < 2; run++) {
        const r = bash(f.script(raw), root)
        assert.equal(r.status, 0, r.stderr)
        assertPinned(root, f)
        assert.equal(existsSync(join(f.source, 'added.txt')), false)
        assert.equal(existsSync(join(f.source, 'untracked')), false)
        assert.equal(readFileSync(join(f.source, 'node_modules/sentinel'), 'utf8'), 'keep dependency')
        assert.equal(readFileSync(join(f.source, 'lib/sentinel'), 'utf8'), 'keep build cache')
      }
    }))
  }
  test(entry + ': missing immutable commit stops before clean, never falls back to HEAD', linux, () => fixture(root => {
    const f = localSource(root, 'existing')
    const head = git(root, f.source, 'rev-parse', 'HEAD')
    writeFileSync(join(f.source, 'source.txt'), 'keep dirt on failure')
    writeFileSync(join(f.source, 'untracked.txt'), 'keep on failure')
    const r = bash(f.script(raw).replaceAll(f.pin, '0'.repeat(40)), root)
    assert.notEqual(r.status, 0)
    assert.equal(git(root, f.source, 'rev-parse', 'HEAD'), head)
    assert.equal(readFileSync(join(f.source, 'source.txt'), 'utf8'), 'keep dirt on failure')
    assert.equal(readFileSync(join(f.source, 'untracked.txt'), 'utf8'), 'keep on failure')
  }))
  for (const kind of ['staging symlink', 'source symlink', 'git symlink', 'gitfile', 'linked worktree', 'core.worktree', 'index symlink']) {
    test(entry + ': rejects ' + kind + ' before modifying external tree', linux, () => fixture(root => {
      const f = localSource(root, 'existing')
      const other = join(root, 'external')
      mkdirSync(other)
      git(root, other, 'init', '-q')
      writeFileSync(join(other, 'sentinel'), 'keep external')
      writeFileSync(join(f.source, 'source.txt'), 'keep dedicated dirt')
      writeFileSync(join(f.source, 'untracked.txt'), 'keep dedicated untracked')
      const head = git(root, f.source, 'rev-parse', 'HEAD')
      if (kind === 'staging symlink') {
        renameSync(join(root, '.deploy-tmp'), join(root, 'redirected-staging'))
        symlinkSync(join(root, 'redirected-staging'), join(root, '.deploy-tmp'))
      } else if (kind === 'source symlink' || kind === 'linked worktree') {
        renameSync(f.source, join(root, 'saved-source'))
        if (kind === 'source symlink') symlinkSync(join(root, 'saved-source'), f.source)
        else git(root, f.upstream, 'worktree', 'add', '--detach', f.source, f.pin)
      } else if (kind === 'git symlink' || kind === 'gitfile') {
        renameSync(join(f.source, '.git'), join(root, 'external-git'))
        if (kind === 'git symlink') symlinkSync(join(root, 'external-git'), join(f.source, '.git'))
        else writeFileSync(join(f.source, '.git'), 'gitdir: ' + join(root, 'external-git') + '\n')
      } else if (kind === 'core.worktree') git(root, f.source, 'config', 'core.worktree', other)
      else {
        renameSync(join(f.source, '.git/index'), join(other, 'external-index'))
        symlinkSync(join(other, 'external-index'), join(f.source, '.git/index'))
      }
      const r = bash(f.script(raw), root)
      assert.notEqual(r.status, 0, r.stderr)
      const kept = kind === 'source symlink' || kind === 'linked worktree' ? join(root, 'saved-source') : f.source
      assert.equal(readFileSync(join(kept, 'source.txt'), 'utf8'), 'keep dedicated dirt')
      assert.equal(readFileSync(join(kept, 'untracked.txt'), 'utf8'), 'keep dedicated untracked')
      if (kind !== 'core.worktree') assert.equal(git(root, kept, 'rev-parse', 'HEAD'), head)
      assert.equal(readFileSync(join(other, 'sentinel'), 'utf8'), 'keep external')
      assert.equal(readFileSync(join(root, '.deploy-tmp/sibling'), 'utf8'), 'keep sibling')
    }))
  }
  for (const key of ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR', 'GIT_OBJECT_DIRECTORY', 'GIT_ALTERNATE_OBJECT_DIRECTORIES', 'GIT_NAMESPACE', 'GIT_CONFIG', 'GIT_CONFIG_COUNT', 'GIT_CONFIG_PARAMETERS']) {
    test(entry + ': inherited ' + key + ' is rejected even when empty', linux, () => fixture(root => {
      const f = localSource(root, 'existing')
      writeFileSync(join(f.source, 'source.txt'), 'keep dirt')
      const r = bash(f.script(raw), root, { [key]: '' })
      assert.notEqual(r.status, 0)
      assert.equal(readFileSync(join(f.source, 'source.txt'), 'utf8'), 'keep dirt')
    }))
  }
}
test('checkout: absent dedicated checkout clones locally and can be reused', linux, () => fixture(root => {
  const f = localSource(root, 'absent')
  for (let run = 0; run < 2; run++) {
    const r = bash(f.script(checkout), root)
    assert.equal(r.status, 0, r.stderr)
    assertPinned(root, f)
  }
}))
test('build entry: blank/misdirected workspace is rejected before source reset', linux, () => fixture(root => {
  const f = localSource(root, 'existing')
  writeFileSync(join(f.source, 'source.txt'), 'keep dirt')
  for (const workspace of ['', f.upstream]) {
    assert.notEqual(bash(f.script(buildEntry), root, { GITHUB_WORKSPACE: workspace }).status, 0)
    assert.equal(readFileSync(join(f.source, 'source.txt'), 'utf8'), 'keep dirt')
  }
}))
const COMPONENT_PIN = '5349432481f8a2b865b8671bf49383c657ece0d1'
// Byte proof from the immutable merged component commit; no remote fetch in this regression.
const COMPONENT_LIB_SHA256 = 'd3250a6b64b5aeb89ce755a4ad4fda95aea2dc69b2cf6df8058056441b677c34'
const component = step('Checkout pinned component sources')
const componentStart = component.indexOf('cmp dsh-host-web-compat/lib/index.js ')
const componentEnd = component.indexOf('\nNODE', componentStart)
assert.ok(componentStart >= 0 && componentEnd > componentStart)
const componentGuard = component.slice(componentStart, componentEnd + '\nNODE'.length)
test('component fetch, checkout, HEAD assertion and provenance bind the same merged immutable commit', () => {
  assert.ok(component.includes('fetch --depth=1 origin ' + COMPONENT_PIN))
  assert.ok(component.includes('checkout --detach ' + COMPONENT_PIN))
  assert.ok(component.includes('rev-parse HEAD)" = "' + COMPONENT_PIN + '"'))
  assert.ok(component.includes("pinnedCommit: '" + COMPONENT_PIN + "'"))
  assert.equal(component.split(COMPONENT_PIN).length - 1, 4)
  assert.equal(component.includes('2de902729e01eb3619aa50bdfa164c5231948848'), false)
  assert.equal(componentGuard.includes('|| true'), false)
  const payload = readFileSync(join(ROOT, 'dsh-host-web-compat/lib/index.js'))
  assert.equal(createHash('sha256').update(payload).digest('hex'), COMPONENT_LIB_SHA256)
})
function componentFixture(root) {
  const pinned = join(root, '.deploy-tmp/component-sources/host-web-compat')
  const mirror = join(root, 'dsh-host-web-compat')
  const payload = readFileSync(join(ROOT, 'dsh-host-web-compat/lib/index.js'))
  const manifest = JSON.parse(readFileSync(join(ROOT, 'dsh-host-web-compat/package.json'), 'utf8'))
  for (const path of [pinned, mirror]) {
    mkdirSync(join(path, 'lib'), { recursive: true })
    writeFileSync(join(path, 'lib/index.js'), payload)
    writeFileSync(join(path, 'package.json'), JSON.stringify(manifest))
  }
  mkdirSync(join(root, 'scripts/snapshot-config'), { recursive: true })
  const overlay = join(root, 'scripts/snapshot-config/engine-overlay.json')
  writeFileSync(overlay, JSON.stringify({ packages: { '@deepseek-ai/cordis': manifest.dependencies['@deepseek-ai/cordis'] } }))
  return { pinned, mirror, manifest, overlay, report: join(root, '.deploy-tmp/source-build/host-web-compat-pin.json') }
}
for (const delta of ['none', 'declared Cordis']) {
  test('component byte guard accepts ' + delta + ' manifest delta and records exact provenance', linux, () => fixture(root => {
    const f = componentFixture(root)
    if (delta === 'declared Cordis') {
      const pinned = structuredClone(f.manifest)
      pinned.dependencies['@deepseek-ai/cordis'] = '4.0.1'
      writeFileSync(join(f.pinned, 'package.json'), JSON.stringify(pinned))
    }
    const r = bash(componentGuard, root)
    assert.equal(r.status, 0, r.stderr)
    const report = JSON.parse(readFileSync(f.report, 'utf8'))
    assert.equal(report.pinnedCommit, COMPONENT_PIN)
    assert.equal(report.pinnedLibSha256, COMPONENT_LIB_SHA256)
    assert.equal(report.mirrorLibSha256, COMPONENT_LIB_SHA256)
    assert.equal(report.libByteIdentical, true)
    assert.deepEqual(report.manifestDelta['@deepseek-ai/cordis'], {
      pinnedCommitPin: delta === 'declared Cordis' ? '4.0.1' : '4.0.4', mirrorPin: '4.0.4', engineOverlayPin: '4.0.4',
    })
  }))
}
for (const drift of ['payload byte', 'extra manifest field', 'wrong Cordis overlay', 'missing Cordis overlay']) {
  test('component guard rejects ' + drift + ' without emitting success provenance', linux, () => fixture(root => {
    const f = componentFixture(root)
    if (drift === 'payload byte') {
      const payload = readFileSync(join(f.mirror, 'lib/index.js'))
      payload[0] ^= 1
      writeFileSync(join(f.mirror, 'lib/index.js'), payload)
    }
    if (drift === 'extra manifest field') writeFileSync(join(f.mirror, 'package.json'), JSON.stringify({ ...f.manifest, version: '0.1.14' }))
    if (drift === 'wrong Cordis overlay') writeFileSync(f.overlay, JSON.stringify({ packages: { '@deepseek-ai/cordis': '4.0.3' } }))
    if (drift === 'missing Cordis overlay') writeFileSync(f.overlay, JSON.stringify({ packages: {} }))
    const r = bash(componentGuard, root)
    assert.notEqual(r.status, 0)
    if (drift === 'payload byte') assert.match(r.stdout + r.stderr, /differ/)
    else if (drift === 'missing Cordis overlay') assert.match(r.stderr, /does not pin/)
    else assert.match(r.stderr, /differs from the pinned component commit/)
    assert.equal(existsSync(f.report), false)
  }))
}
test('deploy cleanup is scoped and rejects empty workspace and linked parent', linux, () => fixture(root => {
  const harness = step('Build and pack DeepSeek Harness from source')
  const cleanupStart = harness.indexOf('# Refuse an empty/misdirected workspace or symlinked staging parent before cleanup.')
  const cleanupEnd = harness.indexOf('\ncorepack pnpm@12.2.0 --pm-on-fail=ignore --filter', cleanupStart)
  assert.ok(cleanupStart >= 0 && cleanupEnd > cleanupStart)
  const cleanup = harness.slice(cleanupStart, cleanupEnd)
  mkdirSync(join(root, '.deploy-tmp/engine-deploy'), { recursive: true })
  writeFileSync(join(root, '.deploy-tmp/sibling'), 'keep')
  assert.equal(bash(cleanup, root).status, 0)
  assert.equal(existsSync(join(root, '.deploy-tmp/engine-deploy')), false)
  assert.equal(readFileSync(join(root, '.deploy-tmp/sibling'), 'utf8'), 'keep')
  assert.ok(harness.includes('git show "$GITHUB_SHA:scripts/snapshot-config/engine-overlay.json" > scripts/snapshot-config/engine-overlay.json'))
  assert.notEqual(bash(cleanup, root, { GITHUB_WORKSPACE: '' }).status, 0)
  const other = join(root, 'other'); mkdirSync(other); writeFileSync(join(other, 'sentinel'), 'keep')
  rmSync(join(root, '.deploy-tmp'), { recursive: true }); symlinkSync(other, join(root, '.deploy-tmp'))
  assert.notEqual(bash(cleanup, root).status, 0)
  assert.equal(readFileSync(join(other, 'sentinel'), 'utf8'), 'keep')
}))

const CLIENT_PACKAGE = '@deepseek-ai/dsh-client-modules'
const ENGINE_MEMBER = 'usr/lib/node_modules/@deepseek-ai/dsh'
const PHASE_MEMBER = ENGINE_MEMBER + '/node_modules/' + CLIENT_PACKAGE + '/lib/index.js'
const assembleSourceBase = step('Assemble clean Termux base with the source-built Android binding')
const materializer = join(ROOT, 'scripts/source-build/materialize-dsh-pnpm-packages.mjs')
const phaseChecker = join(ROOT, 'scripts/check-perf-instrumentation.mjs')
const patchRunner = join(ROOT, 'scripts/patches/apply-patches.mjs')
const clientFixture = join(ROOT, 'scripts/patches/tests/fixtures/dsh-client-modules-0.2.0-rc.2')
function command(args, cwd = ROOT) {
  const r = spawnSync(NODE, args, { cwd, encoding: 'utf8', timeout: 10000, maxBuffer: 1024 * 1024 })
  if (r.error) throw r.error
  return r
}
function sourcePackageFixture(root) {
  const engine = join(root, '.deploy-tmp/engine-deploy')
  const manifest = join(root, '.deploy-tmp/engine-overlay/source-build-manifest.json')
  const report = join(root, '.deploy-tmp/source-build/pnpm-package-materialization.json')
  // Real pinned package identities, dummy payloads except for the versioned client fixture.
  // This is a layout fixture, not a source-build provenance or upstream compilation claim.
  const overlay = JSON.parse(readFileSync(join(ROOT, 'scripts/snapshot-config/engine-overlay.json'), 'utf8'))
  const packages = Object.entries(overlay.packages).filter(([name]) => name.startsWith('@deepseek-ai/'))
    .map(([name, version]) => ({ name, version }))
  assert.ok(packages.length >= 266)
  let physical
  for (const item of packages) {
    const store = join(engine, 'node_modules/.pnpm', item.name.slice(1).replace('/', '+') + '@' + item.version,
      'node_modules', item.name)
    const target = join(engine, 'node_modules', item.name)
    mkdirSync(join(store, 'lib'), { recursive: true })
    mkdirSync(dirname(target), { recursive: true })
    writeFileSync(join(store, 'package.json'), JSON.stringify(item))
    writeFileSync(join(store, 'lib/index.js'), 'export const fixture = true;\n')
    symlinkSync(relative(dirname(target), store), target)
    if (item.name === CLIENT_PACKAGE) {
      physical = store
      copyFileSync(join(clientFixture, 'package.json'), join(store, 'package.json'))
      copyFileSync(join(clientFixture, 'lib/index.js'), join(store, 'lib/index.js'))
    }
  }
  assert.ok(physical)
  mkdirSync(dirname(manifest), { recursive: true })
  writeFileSync(manifest, JSON.stringify({ commit: '639ed015397290b3745d163aafe02ffee4aa3f84', packages }))
  const target = join(engine, 'node_modules', CLIENT_PACKAGE)
  return { engine, manifest, report, target, physical, packageCount: packages.length }
}
function materialize(f) {
  const r = command([materializer, f.engine, f.manifest, f.report])
  assert.equal(r.status, 0, r.stdout + r.stderr)
  return JSON.parse(readFileSync(f.report, 'utf8'))
}
function patchPhase(stage) {
  const r = command([patchRunner, stage, '--apply', '--scope', 'engine', '--only', 'combo-probe-P1'])
  assert.equal(r.status, 0, r.stdout + r.stderr)
  assert.match(r.stdout, /combo-probe-P1/)
}
function writeProfiles(stage) {
  for (const name of ['web', 'headless']) {
    const file = join(stage, 'home/.dsh/profiles', name, 'package.json')
    mkdirSync(dirname(file), { recursive: true })
    writeFileSync(file, JSON.stringify({ name, dsh: { profile: { bundles: ['@deepseek-ai/dsh-' + name] } } }))
  }
}
function copyRuntime(engine, stage) {
  const target = join(stage, ENGINE_MEMBER)
  mkdirSync(dirname(target), { recursive: true })
  cpSync(engine, target, { recursive: true, dereference: false, verbatimSymlinks: true })
  writeProfiles(stage)
}
function realArchive(stage, archive) {
  execFileSync('tar', ['-cf', archive, '-C', stage, 'usr', 'home'])
}
function checkArchive(archive) {
  const args = [phaseChecker, '--require', '--snapshot', archive, '--abi', 'arm64']
  if (!android) return command(args)
  // Normalize only the Android host executable for the checker's real Node child;
  // import the unchanged checker with identical CLI argv, without mocking any gate.
  return command(['--input-type=module', '-e',
    'Object.defineProperty(process, "execPath", { value: ' + JSON.stringify(NODE) + ' });'
    + 'process.argv = [process.execPath, ...process.argv.slice(1)];'
    + 'await import(' + JSON.stringify(phaseChecker) + ');', ...args])
}
function prepareAssemblyBoundary(root, f) {
  mkdirSync(join(root, 'scripts/source-build'), { recursive: true })
  copyFileSync(materializer, join(root, 'scripts/source-build/materialize-dsh-pnpm-packages.mjs'))
  mkdirSync(join(root, 'bin'))
  // Only the authenticated bootstrap/download boundary is stubbed. The extracted
  // workflow shell runs the production materializer; real tar and patcher remain real.
  writeFileSync(join(root, 'bin/python3'), `#!${NODE}
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const args = process.argv.slice(2);
assert.deepEqual(args, ['scripts/source-build/prepare-termux-bootstrap.py',
  '.deploy-tmp/source-build/bootstrap-aarch64.zip', '.deploy-tmp/deepseek-harness',
  '.deploy-tmp/engine-deploy', '.deploy-tmp/arm64-base/base-usr.tar.xz']);
const deploy = path.resolve(args[3]);
const report = JSON.parse(fs.readFileSync('.deploy-tmp/source-build/pnpm-package-materialization.json', 'utf8'));
assert.equal(report.packageCount, ${f.packageCount});
assert.equal(report.convertedSymlinkCount, ${f.packageCount});
for (const item of report.packages) {
  const target = path.join(deploy, 'node_modules', item.name);
  assert.ok(fs.lstatSync(target).isDirectory());
  assert.ok(fs.lstatSync(path.join(target, 'package.json')).isFile());
  assert.ok(fs.lstatSync(path.join(target, 'lib/index.js')).isFile());
}
fs.writeFileSync('bootstrap-boundary-called', deploy);
const stage = path.resolve('bootstrap-fixture');
const engine = path.join(stage, '${ENGINE_MEMBER}');
fs.mkdirSync(path.dirname(engine), { recursive: true });
fs.cpSync(deploy, engine, { recursive: true, dereference: false, verbatimSymlinks: true });
for (const name of ['web', 'headless']) {
  const file = path.join(stage, 'home/.dsh/profiles', name, 'package.json');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify({ name, dsh: { profile: { bundles: ['@deepseek-ai/dsh-' + name] } } }));
}
execFileSync('tar', ['-cf', path.resolve(args[4]), '-C', stage, 'usr', 'home']);
`)
  chmodSync(join(root, 'bin/python3'), 0o755)
}

test('source assembly executes real materialization before bootstrap, not after the strict snapshot gate', linux, () => fixture(root => {
  const f = sourcePackageFixture(root)
  prepareAssemblyBoundary(root, f)
  const r = bash(assembleSourceBase, root)
  assert.equal(r.status, 0, r.stdout + r.stderr)
  assert.equal(readFileSync(join(root, 'bootstrap-boundary-called'), 'utf8'), f.engine)
  assert.ok(lstatSync(join(root, 'bootstrap-fixture', PHASE_MEMBER)).isFile())
  const beforePatch = readFileSync(join(root, 'bootstrap-fixture', PHASE_MEMBER), 'utf8')
  assert.equal(beforePatch, readFileSync(join(clientFixture, 'lib/index.js'), 'utf8'))
  patchPhase(join(root, 'bootstrap-fixture'))
  const archive = join(root, 'built-phase.tar')
  realArchive(join(root, 'bootstrap-fixture'), archive)
  const checked = checkArchive(archive)
  assert.equal(checked.status, 0, checked.stdout + checked.stderr)
  for (const name of ['web', 'headless']) assert.match(checked.stdout, new RegExp('PASS  出厂 profile 清单：' + name))
  assert.match(checked.stdout, /SKIP=0/)
  const snapshot = step('Build runtime snapshot from verified inputs')
  assert.ok(!snapshot.includes('materialize-dsh-pnpm-packages.mjs'))
  assert.ok(snapshot.includes('if node scripts/build-snapshot-013.mjs arm64; then'))
  assert.ok(snapshot.includes('node scripts/source-build/reconcile-engine-patch-copies.mjs'))
  assert.ok(snapshot.includes('node scripts/source-build/check-dsh-source-snapshot.mjs "$snapshot_archive"'))
  assert.equal(yaml.split('node scripts/source-build/materialize-dsh-pnpm-packages.mjs').length - 1, 1)
  assert.ok(yaml.includes('            .deploy-tmp/source-build/pnpm-package-materialization.json'))
  assert.equal(JSON.parse(readFileSync(f.report, 'utf8')).convertedSymlinkCount, f.packageCount)
}))

test('real pnpm archive rejects logical symlink child, then accepts the same patched bytes after materialization', linux, () => fixture(root => {
  const f = sourcePackageFixture(root)
  const beforeStage = join(root, 'before')
  copyRuntime(f.engine, beforeStage)
  patchPhase(beforeStage)
  // Patch through the logical link succeeds on the filesystem, but tar does not
  // synthesize child members under archived symlink directories.
  const patched = readFileSync(join(beforeStage, PHASE_MEMBER), 'utf8')
  const beforeArchive = join(root, 'before.tar')
  realArchive(beforeStage, beforeArchive)
  const logical = spawnSync('tar', ['-xO', '-f', beforeArchive, PHASE_MEMBER], { encoding: 'utf8' })
  assert.notEqual(logical.status, 0)
  assert.match(logical.stderr, /Not found in archive/)
  const physicalMember = ENGINE_MEMBER + '/' + relative(f.engine, join(f.physical, 'lib/index.js')).replaceAll('\\', '/')
  assert.equal(execFileSync('tar', ['-xO', '-f', beforeArchive, physicalMember], { encoding: 'utf8' }), patched)
  const rejected = checkArchive(beforeArchive)
  assert.notEqual(rejected.status, 0)
  assert.ok(rejected.stdout.includes('缺少 ' + PHASE_MEMBER))
  for (const name of ['web', 'headless']) assert.match(rejected.stdout, new RegExp('PASS  出厂 profile 清单：' + name))
  const beforeEngine = join(beforeStage, ENGINE_MEMBER)
  materialize({ ...f, engine: beforeEngine })
  assert.ok(lstatSync(join(beforeStage, PHASE_MEMBER)).isFile())
  assert.equal(readFileSync(join(beforeStage, PHASE_MEMBER), 'utf8'), patched)
  const afterArchive = join(root, 'after.tar')
  realArchive(beforeStage, afterArchive)
  assert.equal(execFileSync('tar', ['-xO', '-f', afterArchive, PHASE_MEMBER], { encoding: 'utf8' }), patched)
  const accepted = checkArchive(afterArchive)
  assert.equal(accepted.status, 0, accepted.stdout + accepted.stderr)
  assert.match(accepted.stdout, /SKIP=0/)
}))

for (const defect of ['markerless payload', 'missing phase output']) {
  test('materialized real tar still fails strict gate for ' + defect, linux, () => fixture(root => {
    const f = sourcePackageFixture(root)
    materialize(f)
    const stage = join(root, 'negative')
    copyRuntime(f.engine, stage)
    if (defect === 'missing phase output') {
      patchPhase(stage)
      const target = join(stage, PHASE_MEMBER)
      const patched = readFileSync(target, 'utf8')
      assert.ok(patched.includes('[perf] phase name=${phase.name}'))
      writeFileSync(target, patched.replace('[perf] phase name=${phase.name}', '[perf] phase label=${phase.name}'))
    }
    const archive = join(root, 'negative.tar')
    realArchive(stage, archive)
    const r = checkArchive(archive)
    assert.notEqual(r.status, 0)
    assert.match(r.stdout, /FAIL  快照内产品 index\.js 携带当前 phase 探针/)
    assert.match(r.stdout, /phase 签名缺失/)
    assert.ok(!r.stdout.includes('缺少 ' + PHASE_MEMBER))
    if (defect === 'missing phase output') assert.ok(r.stdout.includes('[perf] phase name=${phase.name}'))
    for (const name of ['web', 'headless']) assert.match(r.stdout, new RegExp('PASS  出厂 profile 清单：' + name))
  }))
}

test('materialization preserves local dependency precedence, store fallback, reverse links and idempotent bytes', linux, () => fixture(root => {
  const f = sourcePackageFixture(root)
  const peers = dirname(dirname(f.physical))
  for (const [base, name, value] of [[join(f.physical, 'node_modules'), 'fixture-shared', 'local'],
    [peers, 'fixture-shared', 'peer'], [peers, 'fixture-fallback', 'fallback']]) {
    const target = join(base, name)
    mkdirSync(target, { recursive: true })
    writeFileSync(join(target, 'package.json'), JSON.stringify({ name, version: '1.0.0', main: 'index.cjs' }))
    writeFileSync(join(target, 'index.cjs'), 'module.exports = ' + JSON.stringify(value) + ';\n')
  }
  const lookup = base => {
    const req = createRequire(join(base, 'lib/probe.cjs'))
    return ['fixture-shared', 'fixture-fallback'].map(name => ({ value: req(name), resolved: req.resolve(name) }))
  }
  const expected = lookup(f.physical)
  assert.deepEqual(expected.map(item => item.value), ['local', 'fallback'])
  const payload = readFileSync(join(f.target, 'lib/index.js'))
  const converted = materialize(f)
  assert.equal(converted.convertedSymlinkCount, f.packageCount)
  assert.equal(converted.sourceCommit, '639ed015397290b3745d163aafe02ffee4aa3f84')
  assert.deepEqual(lookup(f.target), expected)
  assert.ok(lstatSync(join(f.physical, 'lib')).isSymbolicLink())
  assert.ok(lstatSync(join(f.physical, 'package.json')).isSymbolicLink())
  assert.equal(realpathSync(join(f.physical, 'lib/index.js')), join(f.target, 'lib/index.js'))
  assert.deepEqual(readFileSync(join(f.physical, 'lib/index.js')), payload)
  // The second invocation uses a distinct report so conversion evidence is not overwritten.
  const repeated = materialize({ ...f, report: join(root, 'idempotence-report.json') })
  assert.equal(repeated.convertedSymlinkCount, 0)
  assert.equal(repeated.packageCount, f.packageCount)
  assert.ok(repeated.packages.every(item => item.alreadyMaterialized))
  assert.deepEqual(readFileSync(join(f.target, 'lib/index.js')), payload)
  assert.deepEqual(lookup(f.target), expected)
  assert.equal(JSON.parse(readFileSync(f.report, 'utf8')).convertedSymlinkCount, f.packageCount)
}))

test('materialization identity failure stops the actual assembly shell before bootstrap or success evidence', linux, () => fixture(root => {
  const f = sourcePackageFixture(root)
  prepareAssemblyBoundary(root, f)
  writeFileSync(join(f.physical, 'package.json'), JSON.stringify({ name: CLIENT_PACKAGE, version: '0.1.7-rc.2' }))
  const r = bash(assembleSourceBase, root)
  assert.notEqual(r.status, 0)
  assert.match(r.stderr, /deploy identity mismatch/)
  assert.equal(existsSync(join(root, 'bootstrap-boundary-called')), false)
  assert.equal(existsSync(f.report), false)
  assert.equal(existsSync(join(root, '.deploy-tmp/arm64-base/base-usr.tar.xz')), false)
}))
