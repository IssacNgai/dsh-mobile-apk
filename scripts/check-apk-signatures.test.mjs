import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { buildVerifyArgs, evaluateVerification, readSchemes } from './check-apk-signatures.mjs'

const full = [
  'Verifies',
  'Verified using v1 scheme (JAR signing): true',
  'Verified using v2 scheme (APK Signature Scheme v2): true',
  'Verified using v3 scheme (APK Signature Scheme v3): true',
  'Number of signers: 1',
].join('\n')

test('accepts successful apksigner output only when v1, v2, and v3 are true', () => {
  assert.deepEqual(readSchemes(full), { v1: true, v2: true, v3: true })
  assert.equal(evaluateVerification({ exitCode: 0, output: full }).ok, true)
})

test('verifies v1 against the lowest SDK supported by the generated SHA-256 JAR signature', () => {
  assert.deepEqual(buildVerifyArgs('final.apk'), [
    'verify', '--verbose', '--print-certs', '--min-sdk-version', '18', 'final.apk',
  ])
})

for (const scheme of ['v1', 'v2', 'v3']) {
  test(`rejects APK when only ${scheme} is absent`, () => {
    const output = full.replace(new RegExp(`^Verified using ${scheme} scheme.*(?:\\r?\\n|$)`, 'im'), '')
    const result = evaluateVerification({ exitCode: 0, output })
    assert.equal(result.ok, false)
    assert.deepEqual(result.missing, [scheme])
  })

  test(`rejects APK when only ${scheme} is false`, () => {
    const output = full.replace(new RegExp(`(Verified using ${scheme} scheme \\([^\\r\\n]*?\\):\\s*)true`, 'i'), '$1false')
    const result = evaluateVerification({ exitCode: 0, output })
    assert.equal(result.ok, false)
    assert.deepEqual(result.missing, [scheme])
  })
}

test('rejects nonzero apksigner exit even when all scheme lines claim true', () => {
  assert.equal(evaluateVerification({ exitCode: 1, output: full }).ok, false)
})

test('rejects absent scheme lines and noisy output', () => {
  const result = evaluateVerification({ exitCode: 0, output: 'Verification failed\n' })
  assert.equal(result.ok, false)
  assert.deepEqual(result.missing, ['v1', 'v2', 'v3'])
})

test('Gradle debug and release variants explicitly use the all-schemes signing config', () => {
  const candidates = [new URL('../app/build.gradle.kts', import.meta.url), new URL('../dsh-mobile-apk/app/build.gradle.kts', import.meta.url)]
  const gradlePath = candidates.find((candidate) => existsSync(candidate))
  assert.ok(gradlePath, 'app/build.gradle.kts must exist in the APK tree')
  const gradle = readFileSync(gradlePath, 'utf8')
  const signing = /create\("repoDebug"\)\s*\{([\s\S]*?)\n\s*\}/.exec(gradle)?.[1] ?? ''
  for (const scheme of ['V1', 'V2', 'V3']) assert.match(signing, new RegExp(`enable${scheme}Signing\\s*=\\s*true`))
  assert.match(gradle, /release\s*\{\s*isMinifyEnabled\s*=\s*false\s*signingConfig\s*=\s*signingConfigs\.getByName\("repoDebug"\)/)
  assert.match(gradle, /debug\s*\{\s*signingConfig\s*=\s*signingConfigs\.getByName\("repoDebug"\)/)
})
