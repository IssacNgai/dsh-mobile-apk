import { createHash } from 'node:crypto'
import type { UiNode } from './ui-tree.js'

/** Cache equality includes safety metadata and native handle mapping, not merely rendering. */
export function treeFingerprint(nodes: UiNode[], metadata: unknown = null): string {
  return 'fp' + createHash('sha256').update(JSON.stringify([metadata, nodes.map((n) => [
    n.id, n.parentId, n.text, n.desc, n.rid, n.type, n.pkg, n.windowId,
    n.x, n.y, n.cx, n.cy, n.w, n.h, n.depth, n.origPath ?? null,
    n.clickable, n.editable, n.scrollable, n.checked, n.visible, n.enabled, n.password,
  ])])).digest('hex')
}

/** Missing completeness is unknown, never a successful full-tree proof. */
export function completenessText(truncated: boolean | null | undefined): string {
  return truncated === false ? '未截断（仅所选窗口根子树，不代表全屏/其他窗口）'
    : truncated === true ? '已截断：节点/深度/时间预算或子节点读取不完整，仅含部分子树，请重新 dump'
      : '截断状态未知（旧载荷未提供明确字段）'
}
