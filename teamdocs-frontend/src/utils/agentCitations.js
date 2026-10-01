const hasPage = (source) => Number.isInteger(source.pageNumber) && source.pageNumber > 0
const hasChunk = (source) => Number.isInteger(source.chunkIndex) && source.chunkIndex >= 0

export function citationLocation(source) {
  return [hasPage(source) ? `第 ${source.pageNumber} 页` : '', hasChunk(source) ? `片段 ${source.chunkIndex + 1}` : '']
    .filter(Boolean).join(' · ')
}

export function groupAgentCitations(citations = []) {
  const groups = new Map()

  for (const source of citations) {
    const key = source.documentId == null ? `citation:${source.id}` : `${source.documentId}:${source.parseVersion}`
    if (!groups.has(key)) groups.set(key, { key, documentName: source.documentName, sources: [] })

    const group = groups.get(key)
    if (!group.sources.some((item) => item.id === source.id)) group.sources.push(source)
  }

  return [...groups.values()].map((group, index) => {
    const pages = [...new Set(group.sources.filter(hasPage).map((source) => source.pageNumber))].sort((a, b) => a - b)
    const incompletePages = group.sources.some((source) => !hasPage(source))
    const pageLabel = pages.length ? `${incompletePages ? '已知页码：' : ''}第 ${pages.join('、')} 页` : ''
    const location = group.sources.length === 1
      ? citationLocation(group.sources[0])
      : [pageLabel, `${group.sources.length} 个片段`].filter(Boolean).join(' · ')

    return {
      ...group,
      number: index + 1,
      sourceIds: group.sources.map((source) => source.id),
      location,
      title: group.documentName + (location ? '\n' + location : '')
    }
  })
}

export function findMessageCitation(messages, runId, citationId) {
  const message = messages.find((item) => item.role === 'ASSISTANT' && !item.masked && String(item.runId) === String(runId))
  return message?.citations?.find((source) => source.id === citationId)
}
