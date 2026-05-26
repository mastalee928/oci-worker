/** 一级/二级分组树节点（key 与 TenantConfig、InstanceList 一致） */
export interface GroupExpandNode {
  key: string
  children?: GroupExpandNode[]
}

/** 收集所有可展开的分组 key（一级 + 子分组） */
export function collectGroupExpandKeys(
  nodes: GroupExpandNode[],
  includeNode: (node: GroupExpandNode, level: 1 | 2) => boolean = () => true,
): string[] {
  const keys: string[] = []
  for (const g of nodes) {
    if (includeNode(g, 1)) keys.push(g.key)
    if (g.children) {
      for (const c of g.children) {
        if (includeNode(c, 2)) keys.push(c.key)
      }
    }
  }
  return keys
}

/** 是否已全部展开（无分组时视为未全展开） */
export function isAllGroupsExpanded(
  expanded: Set<string> | readonly string[],
  keys: string[],
): boolean {
  if (keys.length === 0) return false
  const set = expanded instanceof Set ? expanded : new Set(expanded)
  return keys.every((k) => set.has(k))
}
