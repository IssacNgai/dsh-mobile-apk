#!/usr/bin/env node
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const REQUIRED = ['v1', 'v2', 'v3']
// The produced APKs use SHA-256 for JAR/v1 signatures. apksigner defaults
// verification's minimum SDK from the APK manifest (26), where it omits v1
// from its compatibility report. API 18 supports this v1 digest, so verify
// against that floor to assert the actual signature block consistently.
const VERIFY_MIN_SDK = 18

export function buildVerifyArgs(apk) {
  return ['verify', '--verbose', '--print-certs', '--min-sdk-version', String(VERIFY_MIN_SDK), apk]
}

export function readSchemes(output) {
  const schemes = {}
  for (const scheme of REQUIRED) {
    const match = output.match(new RegExp(`^[ \\t]*Verified using ${scheme} scheme \\([^\\r\\n]*?\\):[ \\t]*(true|false)[ \\t]*$`, 'im'))
    schemes[scheme] = match ? match[1].toLowerCase() === 'true' : false
  }
  return schemes
}

export function evaluateVerification({ exitCode, output }) {
  const schemes = readSchemes(output)
  const missing = REQUIRED.filter((scheme) => !schemes[scheme])
  return { ok: exitCode === 0 && missing.length === 0, exitCode, schemes, missing }
}

function fail(message) {
  console.error(`APK signature gate: FAIL: ${message}`)
  process.exitCode = 1
}

function resolveApksigner(explicit) {
  if (explicit) return explicit
  const roots = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT]
  if (process.platform === 'win32') {
    roots.push(path.join(process.env.LOCALAPPDATA ?? '', 'Android', 'Sdk'))
    roots.push(path.join(process.env.USERPROFILE ?? '', 'AppData', 'Local', 'Android', 'Sdk'))
  } else {
    roots.push(path.join(process.env.HOME ?? '', 'Android', 'Sdk'), '/usr/local/lib/android/sdk')
  }
  const candidates = []
  for (const root of roots.filter(Boolean)) {
    const buildTools = path.join(root, 'build-tools')
    if (!fs.existsSync(buildTools)) continue
    for (const version of fs.readdirSync(buildTools).sort()) {
      const candidate = path.join(buildTools, version, process.platform === 'win32' ? 'apksigner.bat' : 'apksigner')
      if (fs.existsSync(candidate)) candidates.push(candidate)
      const extensionless = path.join(buildTools, version, 'apksigner')
      if (process.platform === 'win32' && fs.existsSync(extensionless)) candidates.push(extensionless)
    }
  }
  return candidates.at(-1)
}

function parseArgs(args) {
  const parsed = { apks: [], dirs: [], apksigner: undefined }
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i]
    if (arg === '--apk') parsed.apks.push(args[++i])
    else if (arg === '--dir') parsed.dirs.push(args[++i])
    else if (arg === '--apksigner') parsed.apksigner = args[++i]
    else if (arg === '--self-test') parsed.selfTest = true
    else if (arg === '--help' || arg === '-h') parsed.help = true
    else throw new Error(`unknown argument: ${arg}`)
  }
  return parsed
}

function runSelfTest() {
  const full = [
    'Verifies',
    'Verified using v1 scheme (JAR signing): true',
    'Verified using v2 scheme (APK Signature Scheme v2): true',
    'Verified using v3 scheme (APK Signature Scheme v3): true',
  ].join('\n')
  const cases = [
    ['all schemes present', 0, full, true],
    ['v1 absent', 0, full.replace(/^Verified using v1 scheme.*(?:\r?\n|$)/im, ''), false],
    ['v2 absent', 0, full.replace(/^Verified using v2 scheme.*(?:\r?\n|$)/im, ''), false],
    ['v3 absent', 0, full.replace(/^Verified using v3 scheme.*(?:\r?\n|$)/im, ''), false],
    ['nonzero apksigner exit', 1, full, false],
  ]
  let failures = 0
  for (const [name, exitCode, output, expected] of cases) {
    const actual = evaluateVerification({ exitCode, output }).ok
    const ok = actual === expected
    console.log(`${ok ? 'PASS' : 'FAIL'} self-test ${name}: expected=${expected} actual=${actual}`)
    if (!ok) failures += 1
  }
  if (failures) process.exitCode = 1
  else console.log(`PASS ${cases.length}/${cases.length} signature gate self-tests`)
}

function main() {
  let options
  try {
    options = parseArgs(process.argv.slice(2))
  } catch (error) {
    return fail(error.message)
  }
  if (options.help) {
    console.log('Usage: node scripts/check-apk-signatures.mjs (--apk FILE | --dir DIR)+ [--apksigner PATH] [--self-test]')
    return
  }
  if (options.selfTest) return runSelfTest()
  if (!options.apks.length && !options.dirs.length) return fail('provide at least one --apk or --dir')
  const apksigner = resolveApksigner(options.apksigner)
  if (!apksigner || !fs.existsSync(apksigner)) return fail('apksigner not found; pass --apksigner or set ANDROID_HOME/ANDROID_SDK_ROOT')
  const files = [...options.apks]
  for (const dir of options.dirs) {
    if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return fail(`not a directory: ${dir}`)
    const pending = [path.resolve(dir)]
    while (pending.length) {
      const current = pending.pop()
      for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
        const candidate = path.join(current, entry.name)
        if (entry.isDirectory()) pending.push(candidate)
        else if (entry.isFile() && entry.name.toLowerCase().endsWith('.apk')) files.push(candidate)
      }
    }
  }
  const unique = [...new Set(files.map((file) => path.resolve(file)))].sort()
  if (!unique.length) return fail('no APK files found in the supplied paths')
  let failures = 0
  console.log(`APK signature gate: apksigner=${apksigner}`)
  console.log('Required APK signature schemes: v1=true, v2=true, v3=true')
  for (const apk of unique) {
    if (!fs.existsSync(apk) || !fs.statSync(apk).isFile()) {
      console.error(`FAIL ${apk}: file does not exist`)
      failures += 1
      continue
    }
    const args = buildVerifyArgs(apk)
    console.log(`\n$ ${apksigner} ${args.join(' ')}`)
    const result = spawnSync(apksigner, args, { encoding: 'utf8', windowsHide: true, shell: process.platform === 'win32' })
    const output = `${result.stdout ?? ''}${result.stderr ?? ''}`
    console.log(output.trimEnd())
    const status = evaluateVerification({ exitCode: result.status, output })
    console.log(`apksigner exit-code=${result.status ?? 'null'}; schemes v1=${status.schemes.v1} v2=${status.schemes.v2} v3=${status.schemes.v3}`)
    if (result.error || !status.ok) {
      if (result.error) console.error(`spawn error: ${result.error.message}`)
      console.error(`FAIL ${apk}: required schemes missing/false: ${status.missing.join(', ') || 'none'}; exit=${result.status ?? 'null'}`)
      failures += 1
    } else console.log(`PASS ${apk}`)
  }
  if (failures) return fail(`${failures}/${unique.length} APK(s) failed verification`)
  console.log(`PASS all ${unique.length} final APK(s) have v1, v2, and v3 signatures`)
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
