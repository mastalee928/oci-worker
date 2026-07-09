import { computed, ref, type ComputedRef } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  batchMoveTenantGroup,
  createGroup,
  deleteGroup,
  removeTenant,
  renameGroup,
  saveGroupOrder,
} from '../../../api/tenant'
import { collectGroupExpandKeys, isAllGroupsExpanded } from '../../../composables/groupExpandToggle'
import { isFreeTierPlan, isPaygPlan } from '../../../utils/tenantPlan'

export interface GroupNode {
  label: string
  key: string
  children?: GroupNode[]
  tenants: any[]
}

interface TenantGroupCatalog {
  groupData: {
    level1: string[]
    level2: Record<string, string[]>
  }
  ensureGroups: (options?: { force?: boolean; silent?: boolean }) => Promise<void>
  invalidate: () => void
  removeTenantsFromCache: (ids: string[]) => void
}

interface UseTenantGroupsOptions {
  catalog: TenantGroupCatalog
  tableData: ComputedRef<any[]>
  formState: {
    groupLevel1: string
    groupLevel2?: string
  }
  loadData: (expandAfter?: { groupLevel1?: string; groupLevel2?: string }) => Promise<void> | void
  invalidateCatalogAndReload: () => void
}

export function useTenantGroups(options: UseTenantGroupsOptions) {
  const { catalog, tableData, formState, loadData, invalidateCatalogAndReload } = options

  const selectedRowKeys = ref<string[]>([])
  const batchMoveVisible = ref(false)
  const batchMoveLoading = ref(false)
  const batchMoveG1 = ref<string | undefined>(undefined)
  const batchMoveG2 = ref<string | undefined>(undefined)

  const groupData = computed(() => catalog.groupData)

  const renameVisible = ref(false)
  const renameLoading = ref(false)
  const renameOldName = ref('')
  const renameNewName = ref('')
  const renameLevel = ref('1')
  const renameParent = ref('')

  const addSubVisible = ref(false)
  const addSubParent = ref('')
  const addSubName = ref('')

  const groupMgrVisible = ref(false)
  const createGroupFormVisible = ref(false)
  const createGroupName = ref('')
  const createGroupLevel = ref('1')
  const createGroupParent = ref('')
  const createGroupLoading = ref(false)

  const level2Options = computed(() => {
    if (!formState.groupLevel1) return []
    return groupData.value.level2[formState.groupLevel1] || []
  })

  const formGroupLevel1Options = computed(() =>
    (groupData.value.level1 || []).filter((name: string) => name !== '未分组'),
  )

  const batchMoveLevel1Options = computed(() => {
    const set = new Set<string>(groupData.value.level1 || [])
    set.add('未分组')
    return Array.from(set)
  })

  const batchMoveLevel2Options = computed(() => {
    const g1 = batchMoveG1.value
    if (!g1 || g1 === '未分组') return []
    return groupData.value.level2[g1] || []
  })

  function filterGroupOption(input: string, option: any) {
    const label = option?.label ?? option?.children ?? option?.value ?? ''
    return String(label).toLowerCase().includes(input.toLowerCase())
  }

  const groupTree = computed<GroupNode[]>(() => {
    const all = tableData.value
    const gd = groupData.value

    const l1Map = new Map<string, any[]>()
    for (const r of all) {
      const g1 = r.groupLevel1 || '未分组'
      const list = l1Map.get(g1) || []
      list.push(r)
      l1Map.set(g1, list)
    }

    for (const g1 of gd.level1) {
      if (!l1Map.has(g1)) l1Map.set(g1, [])
    }

    const nodes: GroupNode[] = []

    const orderedKeys: string[] = []
    for (const g1 of gd.level1) {
      if (l1Map.has(g1) && !orderedKeys.includes(g1)) orderedKeys.push(g1)
    }
    for (const k of l1Map.keys()) {
      if (!orderedKeys.includes(k)) orderedKeys.push(k)
    }

    for (const l1 of orderedKeys) {
      const items = l1Map.get(l1) || []
      const withL2 = items.filter((r: any) => !!r.groupLevel2)
      const withoutL2 = items.filter((r: any) => !r.groupLevel2)

      const l2Map = new Map<string, any[]>()
      for (const r of withL2) {
        const list = l2Map.get(r.groupLevel2) || []
        list.push(r)
        l2Map.set(r.groupLevel2, list)
      }

      const l2Names = gd.level2[l1] || []
      for (const l2 of l2Names) {
        if (!l2Map.has(l2)) l2Map.set(l2, [])
      }

      const children: GroupNode[] = []
      const orderedL2Keys: string[] = []
      for (const l2 of l2Names) {
        if (l2Map.has(l2) && !orderedL2Keys.includes(l2)) orderedL2Keys.push(l2)
      }
      for (const l2 of l2Map.keys()) {
        if (!orderedL2Keys.includes(l2)) orderedL2Keys.push(l2)
      }
      for (const l2 of orderedL2Keys) {
        const l2Items = l2Map.get(l2) || []
        children.push({ label: l2, key: `${l1}/${l2}`, tenants: l2Items })
      }

      nodes.push({
        label: l1,
        key: l1,
        children: children.length > 0 ? children : undefined,
        tenants: withoutL2,
      })
    }

    return nodes
  })

  function groupTotalCount(group: GroupNode): number {
    return group.tenants.length + (group.children?.reduce((s, c) => s + c.tenants.length, 0) || 0)
  }

  function getAllGroupTenants(group: GroupNode): any[] {
    const all = [...group.tenants]
    if (group.children) {
      for (const c of group.children) all.push(...c.tenants)
    }
    return all
  }

  function getPlanCounts(group: GroupNode): Record<string, number> {
    const all = getAllGroupTenants(group)
    const counts: Record<string, number> = {}
    for (const t of all) {
      const key = isPaygPlan(t.planType) ? 'PAYG' : isFreeTierPlan(t.planType) ? 'FREE' : 'UNKNOWN'
      counts[key] = (counts[key] || 0) + 1
    }
    return counts
  }

  const expandedGroups = ref<Set<string>>(new Set())
  let pendingExpandTarget: { groupLevel1?: string; groupLevel2?: string } | null = null

  function setPendingExpandTarget(target?: { groupLevel1?: string; groupLevel2?: string } | null) {
    pendingExpandTarget = target && typeof target === 'object' ? target : null
  }

  function clearPendingExpandTarget() {
    pendingExpandTarget = null
  }

  function countGroupNodesIncludingSubs(tree: GroupNode[]): number {
    let n = 0
    for (const g of tree) {
      n += 1
      if (g.children?.length) n += g.children.length
    }
    return n
  }

  function applyDefaultExpandAfterLoad() {
    if (pendingExpandTarget) {
      const l1 = (pendingExpandTarget.groupLevel1 || '').trim() || '未分组'
      const next = new Set<string>([l1])
      const l2 = (pendingExpandTarget.groupLevel2 || '').trim()
      if (l2) next.add(`${l1}/${l2}`)
      expandedGroups.value = next
      pendingExpandTarget = null
      return
    }
    const tenantCount = tableData.value.length
    const groupCount = countGroupNodesIncludingSubs(groupTree.value)
    if (tenantCount > 10 || groupCount > 3) {
      expandedGroups.value = new Set()
      return
    }
    expandAllGroupsFromTree()
  }

  function expandAllGroupsFromTree() {
    const next = new Set<string>()
    for (const g of groupTree.value) {
      next.add(g.key)
      if (g.children) {
        for (const c of g.children) next.add(c.key)
      }
    }
    expandedGroups.value = next
  }

  const tenantExpandableKeys = computed(() => collectGroupExpandKeys(groupTree.value))

  const allGroupsExpanded = computed(() =>
    isAllGroupsExpanded(expandedGroups.value, tenantExpandableKeys.value),
  )

  function toggleAllGroups() {
    if (isAllGroupsExpanded(expandedGroups.value, tenantExpandableKeys.value)) {
      expandedGroups.value = new Set()
    } else {
      expandAllGroupsFromTree()
    }
  }

  const dragFromIndex = ref(-1)
  const dragOverIndex = ref(-1)
  const dragOverPos = ref<'top' | 'bottom'>('top')
  const localOrder = ref<string[]>([])
  const subDragParent = ref('')
  const subDragFromIndex = ref(-1)
  const subDragOverIndex = ref(-1)
  const subDragOverPos = ref<'top' | 'bottom'>('top')
  const localSubOrders = ref<Record<string, string[]>>({})

  const displayGroups = computed(() => {
    const source = localOrder.value.length === 0 ? groupTree.value : (() => {
      const map = new Map<string, any>()
      for (const g of groupTree.value) map.set(g.label, g)
      const result: any[] = []
      for (const name of localOrder.value) {
        const g = map.get(name)
        if (g) { result.push(g); map.delete(name) }
      }
      for (const g of map.values()) result.push(g)
      return result
    })()
    if (!Object.keys(localSubOrders.value).length) return source
    return source.map((g: any) => {
      const order = localSubOrders.value[g.label]
      if (!order?.length || !g.children?.length) return g
      const map = new Map<string, any>()
      for (const c of g.children) map.set(c.label, c)
      const children: any[] = []
      for (const name of order) {
        const child = map.get(name)
        if (child) { children.push(child); map.delete(name) }
      }
      for (const child of map.values()) children.push(child)
      return { ...g, children }
    })
  })

  const renderedGroupChildren = (parent: string) => {
    const group = displayGroups.value.find((g: any) => g.label === parent)
    return group?.children || []
  }

  function onSubDragStart(e: DragEvent, parent: string, idx: number) {
    subDragParent.value = parent
    subDragFromIndex.value = idx
    const map = new Map<string, any>()
    for (const child of renderedGroupChildren(parent)) map.set(child.label, child)
    localSubOrders.value = {
      ...localSubOrders.value,
      [parent]: Array.from(map.keys()),
    }
    if (e.dataTransfer) {
      e.dataTransfer.effectAllowed = 'move'
      e.dataTransfer.setData('text/plain', `${parent}:${idx}`)
      e.dataTransfer.setDragImage(e.target as HTMLElement, 0, 0)
    }
  }

  function onSubDragOver(e: DragEvent, parent: string, idx: number) {
    if (subDragFromIndex.value < 0 || subDragParent.value !== parent) return
    e.preventDefault()
    e.stopPropagation()
    const target = e.currentTarget as HTMLElement
    const rect = target.getBoundingClientRect()
    const mid = rect.top + rect.height / 2
    subDragOverPos.value = e.clientY < mid ? 'top' : 'bottom'
    subDragOverIndex.value = idx
  }

  function onSubDrop(e: DragEvent, parent: string, toIdx: number) {
    const fromIdx = subDragFromIndex.value
    if (fromIdx < 0 || subDragParent.value !== parent) return
    e.preventDefault()
    e.stopPropagation()
    if (fromIdx === toIdx) {
      resetSubDrag()
      return
    }

    const names = [...(localSubOrders.value[parent] || renderedGroupChildren(parent).map((c: any) => c.label))]
    const [moved] = names.splice(fromIdx, 1)
    let insertIdx = subDragOverPos.value === 'bottom' ? toIdx + 1 : toIdx
    if (fromIdx < insertIdx) insertIdx -= 1
    if (insertIdx < 0) insertIdx = 0
    if (insertIdx > names.length) insertIdx = names.length
    names.splice(insertIdx, 0, moved)
    localSubOrders.value = { ...localSubOrders.value, [parent]: names }

    resetSubDrag()
    saveGroupOrder({ parent, order: names })
      .then(() => invalidateCatalogAndReload())
      .catch((e: any) => message.error(e?.message || '保存子分组排序失败'))
  }

  function onSubDragEnd() {
    resetSubDrag()
  }

  function resetSubDrag() {
    subDragParent.value = ''
    subDragFromIndex.value = -1
    subDragOverIndex.value = -1
  }

  function onDragStart(e: DragEvent, idx: number) {
    dragFromIndex.value = idx
    if (e.dataTransfer) {
      e.dataTransfer.effectAllowed = 'move'
      e.dataTransfer.setData('text/plain', String(idx))
      e.dataTransfer.setDragImage(e.target as HTMLElement, 0, 0)
    }
    localOrder.value = displayGroups.value.map(g => g.label)
  }

  function onDragOver(e: DragEvent, idx: number) {
    if (dragFromIndex.value < 0) return
    const target = (e.currentTarget as HTMLElement)
    const rect = target.getBoundingClientRect()
    const mid = rect.top + rect.height / 2
    dragOverPos.value = e.clientY < mid ? 'top' : 'bottom'
    dragOverIndex.value = idx
  }

  function onDrop(_e: DragEvent, toIdx: number) {
    const fromIdx = dragFromIndex.value
    if (fromIdx < 0 || fromIdx === toIdx) { resetDrag(); return }

    const names = [...localOrder.value]
    const [moved] = names.splice(fromIdx, 1)
    let insertIdx = dragOverPos.value === 'bottom' ? toIdx + 1 : toIdx
    if (fromIdx < insertIdx) insertIdx -= 1
    if (insertIdx < 0) insertIdx = 0
    if (insertIdx > names.length) insertIdx = names.length
    names.splice(insertIdx, 0, moved)
    localOrder.value = names

    resetDrag()
    saveGroupOrder({ order: names })
      .then(() => invalidateCatalogAndReload())
      .catch((e: any) => message.error(e?.message || '保存分组排序失败'))
  }

  function onDragEnd() {
    resetDrag()
  }

  function resetDrag() {
    dragFromIndex.value = -1
    dragOverIndex.value = -1
  }

  function toggleGroup(key: string) {
    const s = new Set(expandedGroups.value)
    if (s.has(key)) s.delete(key)
    else s.add(key)
    expandedGroups.value = s
  }

  function onSelectChange(keys: string[]) {
    selectedRowKeys.value = keys
  }

  function openRenameGroup(name: string, level: string, parent?: string) {
    renameOldName.value = name
    renameNewName.value = name
    renameLevel.value = level
    renameParent.value = level === '2' ? (parent || '') : ''
    renameVisible.value = true
  }

  async function handleRenameGroup() {
    if (!renameNewName.value.trim()) { message.warning('名称不能为空'); return }
    renameLoading.value = true
    try {
      await renameGroup({
        oldName: renameOldName.value,
        newName: renameNewName.value.trim(),
        level: renameLevel.value,
        parent: renameLevel.value === '2' ? renameParent.value || undefined : undefined,
      })
      message.success('分组已重命名')
      renameVisible.value = false
      invalidateCatalogAndReload()
    } catch (e: any) {
      message.error(e?.message || '重命名失败')
    } finally {
      renameLoading.value = false
    }
  }

  async function handleDeleteGroup(name: string, level: string, parent?: string) {
    Modal.confirm({
      title: '删除分组',
      content: level === '2'
        ? `确定删除子分组「${name}」？该子分组下的租户将保留在一级分组中`
        : `确定删除分组「${name}」？该分组下的租户将移至「未分组」`,
      async onOk() {
        try {
          await deleteGroup({ name, level, parent: level === '2' ? parent || undefined : undefined })
          message.success('分组已删除')
          invalidateCatalogAndReload()
        } catch (e: any) {
          message.error(e?.message || '删除分组失败')
        }
      },
    })
  }

  function handleAddSubGroup(parentName: string) {
    addSubParent.value = parentName
    addSubName.value = ''
    addSubVisible.value = true
  }

  async function handleAddSubGroupConfirm() {
    const name = addSubName.value.trim()
    if (!name) { message.warning('子分组名不能为空'); return }
    try {
      await createGroup({ name, level: '2', parent: addSubParent.value })
      addSubVisible.value = false
      message.success('子分组已添加')
      invalidateCatalogAndReload()
    } catch (e: any) {
      message.error(e?.message || '添加子分组失败')
    }
  }

  function openGroupManager() {
    groupMgrVisible.value = true
    createGroupFormVisible.value = false
  }

  function openCreateGroupForm() {
    createGroupName.value = ''
    createGroupLevel.value = '1'
    createGroupParent.value = ''
    createGroupFormVisible.value = true
  }

  async function handleCreateGroup() {
    const name = createGroupName.value.trim()
    if (!name) { message.warning('分组名不能为空'); return }
    if (createGroupLevel.value === '2' && !createGroupParent.value) {
      message.warning('请选择父分组'); return
    }
    createGroupLoading.value = true
    try {
      await createGroup({
        name,
        level: createGroupLevel.value,
        parent: createGroupLevel.value === '2' ? createGroupParent.value : undefined,
      })
      message.success('分组已创建')
      createGroupFormVisible.value = false
      invalidateCatalogAndReload()
    } catch (e: any) {
      message.error(e?.message || '创建分组失败')
    } finally {
      createGroupLoading.value = false
    }
  }

  function handleMgrAddSub(parentName: string) {
    createGroupName.value = ''
    createGroupLevel.value = '2'
    createGroupParent.value = parentName
    createGroupFormVisible.value = true
  }

  async function handleMgrDeleteGroup(name: string, level: string, parent?: string) {
    try {
      await deleteGroup({ name, level, parent: level === '2' ? parent || undefined : undefined })
      message.success('分组已删除')
      invalidateCatalogAndReload()
    } catch (e: any) {
      message.error(e?.message || '删除分组失败')
    }
  }

  async function openBatchMoveModal() {
    if (!selectedRowKeys.value.length) return
    batchMoveG1.value = undefined
    batchMoveG2.value = undefined
    try {
      await catalog.ensureGroups({ silent: true })
    } catch {
      // 仍可用已有 groupData。
    }
    batchMoveVisible.value = true
  }

  async function confirmBatchMove() {
    if (!selectedRowKeys.value.length) return
    if (!batchMoveG1.value) {
      message.warning('请选择一级分组')
      return Promise.reject()
    }
    const ids = [...selectedRowKeys.value]
    batchMoveLoading.value = true
    try {
      await batchMoveTenantGroup({
        idList: ids,
        groupLevel1: batchMoveG1.value,
        groupLevel2: batchMoveG2.value || undefined,
      })
      const target = {
        groupLevel1: batchMoveG1.value,
        groupLevel2: batchMoveG2.value || undefined,
      }
      message.success('已移动')
      batchMoveVisible.value = false
      selectedRowKeys.value = []
      catalog.invalidate()
      await loadData(target)
    } catch (e: any) {
      message.error(e?.message || '移动失败')
      return Promise.reject()
    } finally {
      batchMoveLoading.value = false
    }
  }

  function handleBatchDelete() {
    const ids = [...selectedRowKeys.value]
    if (!ids.length) return
    Modal.confirm({
      title: '确认批量删除？',
      content: `将删除 ${ids.length} 条配置`,
      async onOk() {
        try {
          await removeTenant({ idList: ids })
          message.success('删除成功')
          catalog.invalidate()
          catalog.removeTenantsFromCache(ids)
          selectedRowKeys.value = []
          loadData()
        } catch (e: any) {
          message.error(e?.message || '删除失败')
        }
      },
    })
  }

  return {
    selectedRowKeys,
    batchMoveVisible,
    batchMoveLoading,
    batchMoveG1,
    batchMoveG2,
    groupData,
    renameVisible,
    renameLoading,
    renameOldName,
    renameNewName,
    renameLevel,
    addSubVisible,
    addSubParent,
    addSubName,
    groupMgrVisible,
    createGroupFormVisible,
    createGroupName,
    createGroupLevel,
    createGroupParent,
    createGroupLoading,
    level2Options,
    formGroupLevel1Options,
    batchMoveLevel1Options,
    batchMoveLevel2Options,
    filterGroupOption,
    groupTree,
    groupTotalCount,
    getAllGroupTenants,
    getPlanCounts,
    expandedGroups,
    setPendingExpandTarget,
    clearPendingExpandTarget,
    applyDefaultExpandAfterLoad,
    expandAllGroupsFromTree,
    tenantExpandableKeys,
    allGroupsExpanded,
    toggleAllGroups,
    dragFromIndex,
    dragOverIndex,
    dragOverPos,
    subDragParent,
    subDragFromIndex,
    subDragOverIndex,
    subDragOverPos,
    displayGroups,
    onSubDragStart,
    onSubDragOver,
    onSubDrop,
    onSubDragEnd,
    resetSubDrag,
    onDragStart,
    onDragOver,
    onDrop,
    onDragEnd,
    resetDrag,
    toggleGroup,
    onSelectChange,
    openRenameGroup,
    handleRenameGroup,
    handleDeleteGroup,
    handleAddSubGroup,
    handleAddSubGroupConfirm,
    openGroupManager,
    openCreateGroupForm,
    handleCreateGroup,
    handleMgrAddSub,
    handleMgrDeleteGroup,
    openBatchMoveModal,
    confirmBatchMove,
    handleBatchDelete,
  }
}
