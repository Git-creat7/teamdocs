import DOMPurify from 'dompurify'
import { renderCitationMarkdown } from './citationMarkdown.js'

export function renderMarkdown(raw, citations = []) {
  const html = renderCitationMarkdown(raw, citations)

  return DOMPurify.sanitize(html, {
    ADD_TAGS: ['button', 'span', 'pre', 'code', 'div', 'i'],
    ADD_ATTR: ['data-citation-ids', 'data-citation-number', 'data-code', 'class', 'target', 'rel', 'title', 'type'],
    ALLOW_DATA_ATTR: true
  })
}
