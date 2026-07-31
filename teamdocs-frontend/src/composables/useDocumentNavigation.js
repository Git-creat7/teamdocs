import { useRouter } from 'vue-router'
import { usePreferencesStore } from '@/stores/preferences'

export function useDocumentNavigation() {
  const router = useRouter()
  const preferences = usePreferencesStore()

  function openDocument({
    spaceId,
    documentId,
    tab,
    forceWorkspace = false,
    workspaceRoute
  }) {
    if (!spaceId || !documentId) return

    const targetTab = tab || preferences.defaultDetailTab
    const shouldOpenNewTab = !forceWorkspace
      && tab !== 'comments'
      && preferences.documentOpenMode === 'new-tab'

    if (shouldOpenNewTab) {
      const href = router.resolve({
        name: 'DocumentPreview',
        params: { spaceId, documentId }
      }).href
      window.open(href, '_blank', 'noopener,noreferrer')
      return
    }

    return router.push(workspaceRoute || {
      path: `/spaces/${spaceId}`,
      query: { doc: documentId, tab: targetTab }
    })
  }

  return { openDocument }
}
