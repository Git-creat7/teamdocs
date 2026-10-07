import test from 'node:test'
import assert from 'node:assert/strict'
import { ref, computed, watch, nextTick } from 'vue'

function createSidebarState(initialCollapsed = false, initialAutoCollapse = true) {
  const collapsed = ref(initialCollapsed)
  const autoCollapseSidebar = ref(initialAutoCollapse)
  const isMobile = ref(false)

  const route = ref({
    name: 'Home',
    path: '/home',
    params: {},
    query: {}
  })

  const detailSidebarExpanded = ref(false)
  const agentSidebarExpanded = ref(false)

  const isAgentPage = computed(() => route.value.name === 'SpaceAgent')
  const hasDocumentDetail = computed(() =>
    route.value.name === 'SpaceWorkbench' && Number(route.value.query?.doc) > 0
  )
  const automaticDetailCollapse = computed(() =>
    autoCollapseSidebar.value
    && hasDocumentDetail.value
    && !detailSidebarExpanded.value
    && !isMobile.value
  )
  const automaticAgentCollapse = computed(() =>
    isAgentPage.value
    && !agentSidebarExpanded.value
    && !isMobile.value
  )
  const automaticSidebarCollapse = computed(() =>
    automaticDetailCollapse.value || automaticAgentCollapse.value
  )
  const effectiveCollapsed = computed(() =>
    (collapsed.value || automaticSidebarCollapse.value) && !isMobile.value
  )

  function toggleSidebar() {
    if (isMobile.value) return

    if (effectiveCollapsed.value) {
      if (automaticAgentCollapse.value && !collapsed.value) {
        agentSidebarExpanded.value = true

        return
      }

      if (automaticDetailCollapse.value && !collapsed.value) {
        detailSidebarExpanded.value = true

        return
      }

      collapsed.value = false
      agentSidebarExpanded.value = true
      detailSidebarExpanded.value = true

      return
    }

    if (isAgentPage.value && agentSidebarExpanded.value && !collapsed.value) {
      agentSidebarExpanded.value = false

      return
    }

    if (hasDocumentDetail.value && detailSidebarExpanded.value && !collapsed.value) {
      detailSidebarExpanded.value = false

      return
    }

    collapsed.value = true
  }

  watch(
    [() => route.value.name, () => route.value.params.spaceId, () => route.value.query.doc, autoCollapseSidebar],
    () => {
      detailSidebarExpanded.value = false
      agentSidebarExpanded.value = false
    },
    { immediate: true }
  )

  return {
    collapsed,
    autoCollapseSidebar,
    isMobile,
    route,
    detailSidebarExpanded,
    agentSidebarExpanded,
    isAgentPage,
    hasDocumentDetail,
    automaticAgentCollapse,
    effectiveCollapsed,
    toggleSidebar
  }
}

test('entering agent page automatically collapses the sidebar', async () => {
  const state = createSidebarState(false)

  assert.equal(state.effectiveCollapsed.value, false, 'initially expanded on Home')

  // Navigate to Agent page
  state.route.value = {
    name: 'SpaceAgent',
    path: '/spaces/1/agent',
    params: { spaceId: 1 },
    query: {}
  }
  await nextTick()

  assert.equal(state.isAgentPage.value, true)
  assert.equal(state.effectiveCollapsed.value, true, 'automatically collapsed on Agent page')
  assert.equal(state.collapsed.value, false, 'global preference remains unmutated')
})

test('manually toggling sidebar on agent page expands and collapses it without altering base preference', async () => {
  const state = createSidebarState(false)

  // Navigate to Agent page
  state.route.value = {
    name: 'SpaceAgent',
    path: '/spaces/1/agent',
    params: { spaceId: 1 },
    query: {}
  }
  await nextTick()

  assert.equal(state.effectiveCollapsed.value, true, 'auto-collapsed upon entry')

  // User expands sidebar while on Agent page
  state.toggleSidebar()

  assert.equal(state.effectiveCollapsed.value, false, 'expanded after toggle')
  assert.equal(state.agentSidebarExpanded.value, true)
  assert.equal(state.collapsed.value, false, 'base preference is still false')

  // User collapses sidebar again while on Agent page
  state.toggleSidebar()

  assert.equal(state.effectiveCollapsed.value, true, 're-collapsed after second toggle')
  assert.equal(state.agentSidebarExpanded.value, false)
  assert.equal(state.collapsed.value, false, 'base preference is still false')
})

test('navigating away from agent page restores normal expanded state', async () => {
  const state = createSidebarState(false)

  state.route.value = {
    name: 'SpaceAgent',
    path: '/spaces/1/agent',
    params: { spaceId: 1 },
    query: {}
  }
  await nextTick()

  assert.equal(state.effectiveCollapsed.value, true, 'collapsed on Agent page')

  // Navigate back to Home
  state.route.value = {
    name: 'Home',
    path: '/home',
    params: {},
    query: {}
  }
  await nextTick()

  assert.equal(state.effectiveCollapsed.value, false, 'restored to expanded on Home')
})

test('switching between spaces re-triggers auto-collapse for agent page', async () => {
  const state = createSidebarState(false)

  state.route.value = {
    name: 'SpaceAgent',
    path: '/spaces/1/agent',
    params: { spaceId: 1 },
    query: {}
  }
  await nextTick()
  state.toggleSidebar() // user temporarily expanded on space 1

  assert.equal(state.effectiveCollapsed.value, false)

  // User switches to space 2 agent page
  state.route.value = {
    name: 'SpaceAgent',
    path: '/spaces/2/agent',
    params: { spaceId: 2 },
    query: {}
  }
  await nextTick()

  assert.equal(state.effectiveCollapsed.value, true, 'auto-collapses again on space switch')
})
