// Synthetic producer/consumer tests; no Android getter or live-device claims.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { encodeV2, decodeV2, cacheFromV2, rowsFromRaw } from '../lib/protocol-v2.js'
import { pruneNodes } from '../lib/ui-tree.js'
import { detailRecord } from '../lib/detail-store.js'
import { treeFingerprint, completenessText } from '../lib/tree-fingerprint.js'
import { validateJsonSchemaValue } from '@deepseek-ai/dsh-tools'
import { apply } from '../lib/index.js'

const fixtures = JSON.parse(readFileSync(new URL('./fixtures/protocol-v2/password-v1-golden.json', import.meta.url)))
for (const f of fixtures) test(`password golden: ${f.name}`, () => {
  const wire = encodeV2(f.rows, 'all', 7, 0, 1000, 2000)
  assert.deepEqual(wire, f.wire)
  const d = decodeV2(wire)
  assert.equal(d.ok, true)
  assert.deepEqual(d.value.rows.map((n, i) => ({ password: n.password, rid: n.rid,
    visible: n.visible, enabled: n.enabled, originalRow: d.value.origRow[i] })), f.expected)
})
const base = fixtures[0].rows[0]
const proof = { v: 1, kind: 'selected-root', displayId: 0, selectedWindowId: '7', windowIds: ['7'], inventoryComplete: true }
const encode = (rows = [base], view = 'all') => encodeV2(rows, view, 7, 0, 1000, 2000)

test('strict pwv/pw pair, version, dense column, length and empty-tree validation', () => {
  for (const wire of [encode(), encode([])]) {
    for (const extension of [ { pwv: '1', pw: [false] }, { pwv: 2, pw: [false] },
      { pwv: null, pw: [false] }, { pwv: 1, pw: ['false'] }, { pwv: 1, pw: [0] },
      { pwv: 1, pw: [undefined] }, { pwv: 1, pw: Array(1) }, { pwv: 1, pw: [false, false] } ]) {
      assert.equal(decodeV2({ ...wire, ...extension }).ok, false, JSON.stringify(extension))
    }
    const missingPw = { ...wire }; delete missingPw.pw
    const missingVersion = { ...wire }; delete missingVersion.pwv
    assert.equal(decodeV2(missingPw).ok, false)
    assert.equal(decodeV2(missingVersion).ok, false)
    const legacy = { ...wire }; delete legacy.pw; delete legacy.pwv
    const d = decodeV2(legacy)
    assert.equal(d.ok, true)
    assert.ok(d.value.rows.every(n => n.password === null))
  }
  assert.equal(decodeV2({ ...encode(), pw: [] }).ok, false)
  assert.equal(decodeV2({ ...encode([]), pw: [null] }).ok, false)
  assert.equal(decodeV2({ ...encode([base, base, base]), pw: [false, null, true] }).ok, true)
  assert.equal(decodeV2({ ...encode(), r: [999] }).ok, false, 'RID must not silently become empty on bad symbol index')
})

test('all/target preserve hidden RID, missing-RID editable/password, duplicate identity and ancestors', () => {
  const rows = [
    { ...base, rid: '', flag: 0, w: 0, h: 0, password: null },
    { ...base, depth: 1, rid: '', flag: 0, w: 0, h: 0, password: null }, // filtered
    { ...base, depth: 1 },
    { ...base, depth: 1, flag: 0, w: 0, h: 0 }, // same RID, hidden/noneditable/zero-area
    { ...base, depth: 1, flag: 0, rid: '', password: true, w: 0, h: 0 },
    { ...base, depth: 1, rid: '', flag: 4, w: 0, h: 0, password: null },
    { ...base, depth: 1, rid: '' },
    { ...base, depth: 1, rid: '' }, // same-position no-RID editable duplicate
  ]
  for (const view of ['all', 'target']) {
    const wire = encode(rows, view)
    assert.deepEqual(wire.o, [0, 2, 3, 4, 5, 6, 7])
    const d = decodeV2(wire).value
    const cache = cacheFromV2(d)
    assert.deepEqual(cache.nodes.map(n => n.origPath), ['0', '2', '3', '4', '5', '6', '7'])
    assert.equal(cache.nodes.filter(n => n.rid === base.rid).length, 2)
    assert.equal(cache.nodes[2].visible, false)
    assert.equal(cache.nodes[2].editable, false)
    assert.deepEqual(cache.nodes.map(n => detailRecord(n).password), [null, false, false, true, null, false, false])
  }
})

test('XML/V1 metadata preserves explicit truth, unknown and full unsynthesized IDs', () => {
  const rid = 'pkg:id/' + 'q'.repeat(160)
  const raw = [undefined, 'false', 'true', 'FALSE', '0'].map((password, i) => ({
    id: String(i), parentId: '', attrs: { bounds: '[0,0][0,0]', editable: 'true',
      'resource-id': i === 4 ? '' : rid, password, enabled: 'false' },
  }))
  assert.deepEqual(rowsFromRaw(raw).map(r => r.password), [null, false, true, null, null])
  const nodes = pruneNodes(raw).nodes
  assert.equal(nodes.length, 5)
  assert.deepEqual(nodes.map(n => n.password), [null, false, true, null, null])
  assert.deepEqual(nodes.map(n => n.rid), [rid, rid, rid, rid, ''])
  assert.ok(nodes.every(n => n.enabled === false))
})

test('completeness is explicit, malformed rejects, missing unknown even for empty trees', () => {
  for (const wire of [encode(), encode([])]) {
    for (const bad of ['false', 0, null, undefined]) assert.equal(decodeV2({ ...wire, truncated: bad }).ok, false)
    for (const truth of [false, true]) assert.equal(decodeV2({ ...wire, truncated: truth }).value.truncated, truth)
    const legacy = { ...wire }; delete legacy.truncated
    assert.equal(decodeV2(legacy).value.truncated, null)
  }
  assert.match(completenessText(false), /未截断.*仅所选窗口/)
  assert.doesNotMatch(completenessText(null), /未截断/)
  assert.match(completenessText(true), /已截断/)
})

test('fingerprint includes every safety metadata dimension and original mapping', () => {
  const node = cacheFromV2(decodeV2(encode()).value).nodes[0]
  for (const [key, value] of Object.entries({ password: true, enabled: false, visible: false,
    rid: '', pkg: 'other', windowId: 'other', origPath: '9' })) {
    assert.notEqual(treeFingerprint([node]), treeFingerprint([{ ...node, [key]: value }]), key)
  }
  for (const value of [true, null]) assert.notEqual(treeFingerprint([node], false), treeFingerprint([node], value))
})

test('actual dump/cache/detail path refreshes on metadata-only or completeness-only changes', async () => {
  let data = encode()
  const tools = []
  apply({ logger: () => ({ warn() {}, debug() {} }), tools: { register: t => tools.push(t) },
    get: () => undefined, androidPrivilege: {
      gateFor: () => ({ ok: true }), audit() {}, controlDecision: () => ({ backend: 'a11y' }),
      controlExec: async () => ({ ok: true, data }),
      execAdbLine: async () => ({ ok: true, stdout: '' }),
    } })
  const dump = args => tools.find(t => t.name === 'android_ui_dump').execute(args, { agent: { session: 'metadata-test' } })
  const first = await dump({})
  assert.equal(first.nodes[0].password, false)
  assert.equal((await dump({})).unchanged, true)
  let previous = first
  for (const next of [ { ...encode(), pw: [true] }, { ...encode(), pw: [null] },
    encode([{ ...base, rid: 'changed' }]), encode([{ ...base, flag: 20 }]),
    { ...encode(), truncated: true }, (() => { const x = encode(); delete x.truncated; return x })(), encode(),
    { ...encode(), snapshotScope: proof },
    { ...encode(), snapshotScope: proof, strictInputIdentity: 1 },
    { ...encode(), snapshotScope: { ...proof, inventoryComplete: false } },
    { ...encode(), snapshotScope: { ...proof, selectedWindowId: '8', windowIds: ['8'] } },
    { ...encode(), snapshotScope: { ...proof, windowIds: ['7', '8'], inventoryComplete: false } },
  ]) {
    data = next
    const result = await dump({})
    assert.equal(result.ok, true)
    assert.deepEqual(validateJsonSchemaValue(tools.find(t => t.name === 'android_ui_dump').output.schema, result), [])
    assert.deepEqual(result.snapshotScope, decodeV2(next).value.snapshotScope)
    assert.equal(result.truncated, decodeV2(next).value.truncated)
    assert.equal(result.strictInputIdentity, decodeV2(next).value.strictInputIdentity)
    assert.notEqual(result.unchanged, true)
    assert.equal(result.nodes[0].password, decodeV2(next).value.rows[0].password)
    assert.notEqual(result.detailHandle, previous.detailHandle, 'detail handle must include changed node or completeness metadata')
    previous = result
  }
  // Malformed extension never falls back to a V1 interpretation.
  data = { ...encode(), pwv: '1' }
  assert.equal((await dump({ fresh: true })).ok, false)
})


test('strict versioned snapshotScope proof validates before empty-tree fast path', () => {
  for (const wire of [encode(), encode([])]) {
    assert.equal(decodeV2(wire).value.snapshotScope, null)
    assert.deepEqual(decodeV2({ ...wire, snapshotScope: proof }).value.snapshotScope, proof)
    const invalid = [null, [], 'all', {}, { ...proof, v: '1' }, { ...proof, v: 2 },
      { ...proof, kind: 'full-screen' }, { ...proof, displayId: '0' }, { ...proof, displayId: 1 },
      { ...proof, selectedWindowId: '' }, { ...proof, selectedWindowId: '-1' },
      { ...proof, selectedWindowId: '7\n', windowIds: ['7\n'] },
      { ...proof, selectedWindowId: '7\r', windowIds: ['7\r'] },
      { ...proof, windowIds: ['7\u2028'] }, { ...proof, windowIds: ['07'] }, { ...proof, windowIds: [7] }, { ...proof, windowIds: Array(1) },
      { ...proof, windowIds: [] }, { ...proof, windowIds: ['7', '7'] }, { ...proof, windowIds: ['7', '8'] },
      { ...proof, windowIds: ['8'] }, { ...proof, inventoryComplete: 'true' }, Object.create(proof)]
    for (const snapshotScope of invalid) assert.equal(decodeV2({ ...wire, snapshotScope }).ok, false, JSON.stringify(snapshotScope))
    assert.equal(decodeV2({ ...wire, snapshotScope: { ...proof, windowIds: [], selectedWindowId: '', inventoryComplete: false } }).ok, true)
  }
  assert.equal(decodeV2({ ...encode(), raw: 2147483648, o: [2147483647] }).ok, false)
})


test('strict capability is explicit versioned evidence even for empty trees', () => {
  for (const wire of [encode(), encode([])]) {
    assert.equal(decodeV2(wire).value.strictInputIdentity, null)
    assert.equal(decodeV2({ ...wire, strictInputIdentity: 1 }).value.strictInputIdentity, 1)
    for (const bad of [null, undefined, true, false, '1', 0, 2, {}, []]) {
      assert.equal(decodeV2({ ...wire, strictInputIdentity: bad }).ok, false)
    }
  }
})

test('strict input gates producer support and exact identity before every backend; requires receipt', async () => {
  const goodRow = { ...base, windowId: '7' }
  const good = () => ({ ...encode([goodRow]), snapshotScope: proof, strictInputIdentity: 1 })
  const identity = { v: 1, packageName: base.pkg, windowId: '7', resourceId: base.rid, className: 'EditText', password: false }
  let data = good(), backend = 'a11y', receipt = { strictIdentityVerified: true }
  const actions = [], tools = []
  apply({ logger: () => ({ warn() {}, debug() {} }), tools: { register: t => tools.push(t) },
    get: () => undefined, androidPrivilege: {
      gateFor: () => ({ ok: true }), audit() {}, controlDecision: () => ({ backend }),
      controlExec: async (op, args) => { if (op === 'snapshot') return { ok: true, data }; actions.push([op, args]); return { ok: true, data: receipt } },
      execAdbLine: async (...args) => { actions.push(['adb', args]); return { ok: true, stdout: '' } },
      execAdbShell: async (...args) => { actions.push(['adbShell', args]); return { ok: true, stdout: '' } },
    } })
  const context = { agent: { session: 'strict-test' } }
  const dump = () => tools.find(t => t.name === 'android_ui_dump').execute({ fresh: true }, context)
  const tool = tools.find(t => t.name === 'android_ui_input')
  const input = async (extra = {}) => {
    try { return await tool.execute({ text: '你好，我是JEV', clear: true, ref: 'id:n0', strictIdentity: identity, ...extra }, context) }
    catch (error) { assert.equal(error.code, 'INVALID_ARGS'); return { ok: false, denied: true } }
  }
  await dump()
  actions.length = 0
  const success = await input()
  assert.equal(success.ok, true)
  assert.equal(success.strictIdentityVerified, true)
  assert.deepEqual(validateJsonSchemaValue(tool.output.schema, success), [])
  assert.deepEqual(actions[0], ['setText', { text: '你好，我是JEV', clear: true, gen: 7, screenId: 'real', strictIdentity: identity, row: 0 }])
  for (const extra of [ { strictIdentity: undefined }, { strictIdentity: null }, { strictIdentity: true }, { strictIdentity: {} },
    { strictIdentity: { ...identity, password: true } }, { strictIdentity: { ...identity, resourceId: 'wrong' } },
    { strictIdentity: { ...identity, className: 'android.widget.EditText' } }, { strictIdentity: { ...identity, packageName: 'other' } },
    { ref: 'w0' }, { ref: 'css:input' }, { ref: undefined }, { screenId: 'virtual-1' }, { channel: 'input' } ]) {
    actions.length = 0
    assert.equal((await input(extra)).ok, false, JSON.stringify(extra))
    assert.deepEqual(actions, [])
  }
  for (const next of [ { ...good(), strictInputIdentity: undefined }, { ...encode([goodRow]), snapshotScope: proof },
    { ...good(), truncated: true }, { ...good(), snapshotScope: { ...proof, inventoryComplete: false } },
    { ...good(), ...encode([{ ...goodRow, password: true }]) },
    { ...good(), ...encode([goodRow, { ...goodRow, flag: 0, w: 0, h: 0 }]) } ]) {
    // Failed/malformed observations clear prior cache rather than preserving strict authority.
    data = next
    const dumped = await dump()
    actions.length = 0
    assert.equal((await input()).ok, false)
    assert.deepEqual(actions, [])
  }
  data = good(); await dump(); backend = 'adb'; actions.length = 0
  assert.equal((await input()).ok, false); assert.deepEqual(actions, [])
  backend = 'a11y'; receipt = {}; actions.length = 0
  assert.equal((await input()).ok, false)
  assert.equal(actions.length, 1)
})

test('editable field values preserve surrounding and whitespace-only text exactly', () => {
  for (const text of [' 你好，我是JEV ', '   ', '\tquery\n']) {
    const d = decodeV2(encode([{ ...base, text }])).value
    assert.equal(d.rows[0].text, text)
  }
})
