import test from 'node:test'
import assert from 'node:assert/strict'
import { isTaskSubmitted, PRIVILEGE_STATUS_PROBE, readPrivilegeStatusFromPage, isKnownScreenScope, reconcileCreatedVdisplay, restoreScopeVerified, shouldDestroyVdisplay } from './verify-screen-scope-matrix.mjs'

test('scope restoration detects a setter that succeeds without changing the source of truth', async () => {
  let current = 'real-only'
  const result = await restoreScopeVerified('virtual-only', async () => true, async () => current)
  assert.deepEqual(result, {
    ok: false,
    actual: 'real-only',
    reason: 'scope readback mismatch: expected virtual-only, got real-only',
  })
})

test('scope restoration failure is surfaced instead of swallowed', async () => {
  const result = await restoreScopeVerified('all', async () => { throw new Error('bridge closed') }, async () => 'all')
  assert.equal(result.ok, false)
  assert.match(result.reason, /bridge closed/)
})

test('plain wire string readback is accepted only when it matches the original value', async () => {
  const result = await restoreScopeVerified('virtual-only', async () => true, async () => 'virtual-only')
  assert.deepEqual(result, { ok: true, actual: 'virtual-only' })
})

test('pre-existing virtual displays are never owned by this run; keep preserves a newly created one', () => {
  assert.equal(shouldDestroyVdisplay({ createdByRun: false, keep: false }), false)
  assert.equal(shouldDestroyVdisplay({ createdByRun: true, keep: true }), false)
  assert.equal(shouldDestroyVdisplay({ createdByRun: true, keep: false }), true)
})

test('an accepted create with a missing display id is marked inconclusive but still cleaned up', () => {
  const result = reconcileCreatedVdisplay({
    hadVirtualBefore: false,
    createReceipt: { ok: true, state: 'active', selected: 'virtual-1' },
    observedVirtual: { alias: 'virtual-1', displayId: 42 },
  })
  assert.deepEqual(result, { owned: true, receiptMatches: false })
  assert.equal(shouldDestroyVdisplay({ createdByRun: result.owned, keep: false }), true)
})

test('unknown original scopes fail closed instead of being normalized and written back', () => {
  assert.equal(isKnownScreenScope('all'), true)
  assert.equal(isKnownScreenScope('real-only'), true)
  assert.equal(isKnownScreenScope('virtual-only'), true)
  assert.equal(isKnownScreenScope('future-value'), false)
})

test('privilege state is read in the authenticated page without copying cookies to the host', async () => {
  const expected = { gates: { shizukuReady: true } }
  const result = await readPrivilegeStatusFromPage(async (expressions) => {
    assert.deepEqual(expressions, [PRIVILEGE_STATUS_PROBE])
    assert.match(expressions[0], /credentials: 'same-origin'/)
    assert.match(expressions[0], /fetch\('\/api\/android\/privilege\/status'/)
    return [{ status: 200, ok: true, body: expected }]
  })
  assert.deepEqual(result, expected)
})

test('an unauthorized or missing page response cannot pass the privilege probe', async () => {
  await assert.rejects(readPrivilegeStatusFromPage(async () => [{ status: 403, ok: false, body: null }]), /HTTP 403/)
  await assert.rejects(readPrivilegeStatusFromPage(async () => [{ __error: 'page gone' }]), /unavailable/)
})

test('busy, dirty, modal-blocked or missing composers cannot count as submitted model tasks', () => {
  for (const receipt of ['generation-active', 'composer-not-empty', 'dialog-open', 'no-composer', undefined, { __error: 'CDP rejected' }]) {
    assert.equal(isTaskSubmitted(receipt), false)
  }
  assert.equal(isTaskSubmitted('clicked'), true)
  assert.equal(isTaskSubmitted('enter'), true)
})
