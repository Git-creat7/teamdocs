import { ref } from 'vue'
import { listDocumentTagsBatchApi } from '@/api/tag'

/**
 * 文档标签缓存 (docId -> 标签名数组)。
 * 所有失效规则集中在此，调用方不需要知道缓存怎么维护：
 *   - loadForDocs: 列表刷新后对缺失文档一次批量拉取 (不再逐文档 N+1)
 *   - applyToggle: 打标/摘标的本地增量修补
 *   - setFromDetail: 文档详情接口返回的最新标签直接覆盖
 *   - invalidateAll: 标签重命名/删除后全量失效 (下一次 loadForDocs 重拉)
 *   - reset: 切空间时清空
 *
 * 失败语义：批量请求失败时不写缓存 (保持"没查过"状态，下次列表刷新自动重试)，
 * 绝不把失败落成 []——那会被当成"确认无标签"且本会话不再重试。
 */
const BATCH_LIMIT = 200

export function useDocTags(spaceIdRef) {
  const docTagsMap = ref({})

  async function loadForDocs(docs) {
    const missingIds = (docs || [])
      .map((d) => d.id)
      .filter((id) => !(id in docTagsMap.value))
    if (missingIds.length === 0) return

    const patch = {}
    for (let i = 0; i < missingIds.length; i += BATCH_LIMIT) {
      const chunk = missingIds.slice(i, i + BATCH_LIMIT)
      try {
        const map = await listDocumentTagsBatchApi(spaceIdRef.value, chunk)
        // 后端对请求过的 id 都会给出条目 (无标签为 [])；仍以响应实际包含的键为准，
        // 响应里缺席的 id 不落缓存，保持可重试
        for (const [docId, tags] of Object.entries(map)) {
          patch[docId] = (Array.isArray(tags) ? tags : []).map((t) => t.name)
        }
      } catch (err) {
        // 本块失败：整块不落缓存，其余块继续
      }
    }
    if (Object.keys(patch).length > 0) {
      docTagsMap.value = { ...docTagsMap.value, ...patch }
    }
  }

  function applyToggle(docId, tagName, added) {
    const cur = docTagsMap.value[docId] || []
    const next = added ? [...cur, tagName] : cur.filter((n) => n !== tagName)
    docTagsMap.value = { ...docTagsMap.value, [docId]: next }
  }

  function setFromDetail(docId, tags) {
    docTagsMap.value = {
      ...docTagsMap.value,
      [docId]: (Array.isArray(tags) ? tags : []).map((t) => t.name)
    }
  }

  function invalidateAll() {
    docTagsMap.value = {}
  }

  function reset() {
    docTagsMap.value = {}
  }

  return { docTagsMap, loadForDocs, applyToggle, setFromDetail, invalidateAll, reset }
}
