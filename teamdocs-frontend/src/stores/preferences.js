import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export const SIDEBAR_DEFAULT_WIDTH = 232
export const SIDEBAR_MIN_WIDTH = 200
export const SIDEBAR_MAX_WIDTH = 360

const KEYS = {
  sidebarCollapsed: 'teamdocs_sidebar_collapsed',
  sidebarWidth: 'teamdocs_sidebar_width',
  autoCollapseSidebar: 'teamdocs_auto_collapse_sidebar',
  searchScopeMode: 'teamdocs_search_scope_mode',
  documentOpenMode: 'teamdocs_document_open_mode',
  defaultDetailTab: 'teamdocs_default_detail_tab'
}

function readBoolean(key, fallback) {
  const value = localStorage.getItem(key)
  if (value === null) return fallback
  return value === '1'
}

function readEnum(key, allowed, fallback) {
  const value = localStorage.getItem(key)
  return allowed.includes(value) ? value : fallback
}

function clampSidebarWidth(width) {
  return Math.min(SIDEBAR_MAX_WIDTH, Math.max(SIDEBAR_MIN_WIDTH, width))
}

function readSidebarWidth() {
  const value = Number(localStorage.getItem(KEYS.sidebarWidth))
  return Number.isFinite(value) && value > 0
    ? clampSidebarWidth(value)
    : SIDEBAR_DEFAULT_WIDTH
}

export const usePreferencesStore = defineStore('preferences', () => {
  const sidebarCollapsed = ref(readBoolean(KEYS.sidebarCollapsed, false))
  const sidebarWidth = ref(readSidebarWidth())
  const autoCollapseSidebar = ref(readBoolean(KEYS.autoCollapseSidebar, true))
  const searchScopeMode = ref(readEnum(KEYS.searchScopeMode, ['current', 'all'], 'current'))
  const documentOpenMode = ref(readEnum(KEYS.documentOpenMode, ['workspace', 'new-tab'], 'workspace'))
  const defaultDetailTab = ref(readEnum(KEYS.defaultDetailTab, ['preview', 'comments'], 'preview'))

  watch(sidebarCollapsed, (value) => {
    localStorage.setItem(KEYS.sidebarCollapsed, value ? '1' : '0')
  })
  watch(autoCollapseSidebar, (value) => {
    localStorage.setItem(KEYS.autoCollapseSidebar, value ? '1' : '0')
  })
  watch(searchScopeMode, (value) => {
    localStorage.setItem(KEYS.searchScopeMode, value)
  })
  watch(documentOpenMode, (value) => {
    localStorage.setItem(KEYS.documentOpenMode, value)
  })
  watch(defaultDetailTab, (value) => {
    localStorage.setItem(KEYS.defaultDetailTab, value)
  })

  function setSidebarWidth(width) {
    sidebarWidth.value = clampSidebarWidth(width)
  }

  function persistSidebarWidth() {
    localStorage.setItem(KEYS.sidebarWidth, String(Math.round(sidebarWidth.value)))
  }

  function resetSidebarWidth() {
    sidebarWidth.value = SIDEBAR_DEFAULT_WIDTH
    persistSidebarWidth()
  }

  return {
    sidebarCollapsed,
    sidebarWidth,
    autoCollapseSidebar,
    searchScopeMode,
    documentOpenMode,
    defaultDetailTab,
    setSidebarWidth,
    persistSidebarWidth,
    resetSidebarWidth
  }
})
