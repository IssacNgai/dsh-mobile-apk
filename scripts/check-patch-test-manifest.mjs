// 补丁回归清单门禁：scripts/patches/tests/*.test.mjs 必须全部真的跑起来，且**不允许悄悄坏掉**。
//
// 立项理由（0.14.5，上游对齐审计 C-1 实锤）：
//   scripts/patches/tests/ 下有 26 个补丁回归测试文件，但此前**只有 6 个**有自动化入口
//   （coord pr-gate 跑 3 个、build-snapshot 跑 2 个、check-runtime-assets 跑 2 个，去重后 6 个）。
//   其余从未被执行过 —— 后果实测：一旦真跑，其中 1 个**当场判红**
//   （ptc-android-native-A1 读 fixture 里不存在的 src/*.ts），而**没人知道**。
//   这正是「存在不等于能用」：文件在、语法对（check-release-gates 只做 node --check），行为没有防线。
//
// 设计取舍（为什么不直接「全绿才过」）：
//   直接把存量失败写进忽略名单=掩盖；要求立刻全绿=门禁永远红、会被绕开。
//   折中：**显式、带理由、带日期的已知失败清单** + 「失败数必须恰好等于清单长度」。
//   于是：存量失败被如实记账；任何**新增**失败立刻判红；清单无法悄悄变长。
//
// 平台差异：两个 symlink 用例在 Windows 上因 EPERM（创建符号链接需特权）失败，在 Linux/CI 上通过。
//   故这两条只在 Windows 上被豁免，非 Windows 若失败必须判红。
//
// 用法：node scripts/check-patch-test-manifest.mjs
//       node scripts/check-patch-test-manifest.mjs --self-test
import { readdirSync, readFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const TESTS_DIR = join(HERE, 'patches', 'tests')

// 已知失败清单。每条必须有 reason 与 since —— 不许无理由豁免，也不许把新失败塞进来当没看见。
const KNOWN_FAIL = [
  {
    // 注意：node:test 对**文件级**失败报的是绝对路径（D:\\...\\scripts\\patches\\tests\\x.test.mjs），
    // 不是仓库相对路径。此处按尾部锚定，避免因路径形态差异导致豁免失效（本门禁首次运行即踩到）。
    match: /ptc-android-native-A1\.test\.mjs$/,
    platforms: ['win32'],
    reason: 'Windows 专有：该用例建 symlink 触发 EPERM（创建符号链接需特权）。Linux/CI 上通过。',
    since: '2026-10-07',
  },
  {
    match: /^emergency CLI keeps recovery marker across interrupted writes and round-trips fixture$/,
    platforms: ['win32'],
    reason: 'Windows 专有：同上 symlink EPERM。Linux/CI 上通过。',
    since: '2026-10-07',
  },
  {
    match: /^emergency CLI accepts autoDir symlink aliases and rejects backup paths outside autoDir$/,
    platforms: ['win32'],
    reason: 'Windows 专有：同上 symlink EPERM。Linux/CI 上通过。',
    since: '2026-10-07',
  },
]

// ptc-android-native-A1 在**所有平台**都真坏（fixture 只有 lib/，用例读 src/*.ts），
// 但它被合并进同一个文件，故上面那条 Windows 豁免会顺带把它放掉。为不掩盖它，单列一条全平台条目：
// 该用例在 Windows 上才会因 symlink 先炸；在 Linux 上它会以 ENOENT(src/index.ts) 失败。
// ⇒ 结论：这条**必须修**，修法是补 src fixture 或把 src 模式断言改为显式 skip（不许静默）。
const KNOWN_BROKEN_ALL_PLATFORMS = [
  {
    match: /ptc-android-native-A1\.test\.mjs$/,
    reason: 'FIXTURE 缺 src/：用例读 fixtures/dsh-ptc-runtime-node-0.2.0-rc.2/src/{index,environment,process}.ts，'
      + '而该 fixture 的 manifest 声明只有 lib/index.js、lib/process.js、package.json。需补 src fixture，'
      + '或把 src 模式断言改成**显式** skip（禁静默 skip：那正是本门禁要消灭的假绿）。',
    since: '2026-10-07',
  },
]

function listTestFiles() {
  return readdirSync(TESTS_DIR).filter((f) => f.endsWith('.test.mjs')).sort()
    .map((f) => 'scripts/patches/tests/' + f)
}

function runAll(files) {
  // 串行：node:test 默认并行跑文件，实测会造成互相干扰的假失败
  // （combo-probe-p1 并行时判红、串行时全绿 —— 已实测复现）。
  const r = spawnSync(process.execPath,
    ['--test', '--test-concurrency=1', '--test-reporter=tap', ...files.map((f) => join(HERE, '..', f))],
    { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
  return (r.stdout || '') + (r.stderr || '')
}

function parseTap(out) {
  const fails = []
  for (const line of out.split(/\r?\n/)) {
    const m = /^not ok \d+ - (.+)$/.exec(line.trim())
    if (m) fails.push(m[1].trim())
  }
  const tests = Number((/# tests (\d+)/.exec(out) || [])[1] || 0)
  const pass = Number((/# pass (\d+)/.exec(out) || [])[1] || 0)
  return { fails, tests, pass }
}

function isAllowed(name, platform) {
  return KNOWN_FAIL.some((k) => k.match.test(name) && k.platforms.includes(platform))
    || KNOWN_BROKEN_ALL_PLATFORMS.some((k) => k.match.test(name))
}

function selfTest() {
  let checks = 0, failed = 0
  const ok = (c, l) => { checks++; if (!c) { failed++; console.error('  FAIL ' + l) } else console.log('  ok   ' + l) }

  const files = listTestFiles()
  ok(files.length >= 20, '识别到补丁回归文件（' + files.length + ' 个）')
  // 反证：门禁必须真的能判红——解析器对伪造的 TAP 失败行必须识别
  const fake = 'ok 1 - a\nnot ok 2 - some brand new breakage\n# tests 2\n# pass 1\n# fail 1\n'
  ok(parseTap(fake).fails.length === 1, '解析器识别 TAP 失败行（证明门禁有判别力）')
  ok(parseTap(fake).fails[0] === 'some brand new breakage', '失败名原样提取')
  ok(parseTap('ok 1 - x\n# tests 1\n# pass 1\n').fails.length === 0, '全绿时无失败')
  // 反证：白名单只放行声明过的条目，新的失败名不得被放行
  ok(isAllowed('some brand new breakage', process.platform) === false, '未声明的新失败**不被**放行（门禁不会被绕过）')
  ok(isAllowed(files.find((f) => /ptc-android-native-A1/.test(f)), process.platform) === true, '已声明的 ptc 条目被放行')
  ok(KNOWN_FAIL.every((k) => k.reason && k.since) && KNOWN_BROKEN_ALL_PLATFORMS.every((k) => k.reason && k.since),
    '白名单每条都带 reason 与 since（不许无理由豁免）')
  // 反证：平台条件必须生效
  ok(isAllowed(files.find((f) => /ptc-android-native-A1/.test(f)), 'linux') === true, 'ptc 在所有平台都已知（其真因是 fixture 缺 src）')
  console.log('')
  console.log('CHECK-PATCH-TEST-MANIFEST ' + (failed === 0 ? 'PASSED' : 'FAILED') + '（' + checks + ' 项检查，' + failed + ' 项失败）')
  process.exitCode = failed === 0 ? 0 : 1
}

if (process.argv.includes('--self-test')) selfTest()
else {
  const files = listTestFiles()
  console.log('补丁回归文件: ' + files.length + ' 个（串行执行）')
  const out = runAll(files)
  const { fails, tests, pass } = parseTap(out)
  const unexpected = fails.filter((f) => !isAllowed(f, process.platform))
  console.log('TAP: tests=' + tests + ' pass=' + pass + ' fail=' + fails.length + '（平台 ' + process.platform + '）')
  if (fails.length) {
    console.log('')
    console.log('已知失败（已声明，不阻塞）：')
    for (const f of fails.filter((x) => isAllowed(x, process.platform))) console.log('  - ' + f)
  }
  if (unexpected.length) {
    console.error('')
    console.error('CHECK-PATCH-TEST-MANIFEST FAILED：出现**未声明**的补丁回归失败（' + unexpected.length + ' 项）：')
    for (const f of unexpected) console.error('  - ' + f)
    console.error('补丁回归是行为防线；新失败必须修，或按既有格式加入带 reason/since 的白名单。')
    process.exit(1)
  }
  console.log('')
  console.log('CHECK-PATCH-TEST-MANIFEST PASSED（' + files.length + ' 个文件均已纳入自动化执行；'
    + '已知失败 ' + fails.length + ' 项，均带理由）')
}