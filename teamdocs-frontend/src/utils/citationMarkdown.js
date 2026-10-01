import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import { groupAgentCitations } from './agentCitations.js'

const md = new MarkdownIt({ html: false, breaks: true, linkify: true, typographer: false })

md.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
  tokens[index].attrSet('target', '_blank')
  tokens[index].attrSet('rel', 'noopener noreferrer')
  return renderer.renderToken(tokens, index, options)
}

// 自定义代码块渲染：彩色语法高亮 + 右上方语言类型显示 + 一键复制代码
md.renderer.rules.fence = (tokens, index) => {
  const token = tokens[index]
  const info = token.info ? token.info.trim() : ''
  const lang = info ? info.split(/\s+/)[0].toLowerCase() : ''

  let highlighted = ''
  if (lang && hljs.getLanguage(lang)) {
    try {
      highlighted = hljs.highlight(token.content, { language: lang, ignoreIllegals: true }).value
    } catch {
      highlighted = md.utils.escapeHtml(token.content)
    }
  } else if (!lang) {
    try {
      highlighted = hljs.highlightAuto(token.content).value
    } catch {
      highlighted = md.utils.escapeHtml(token.content)
    }
  } else {
    highlighted = md.utils.escapeHtml(token.content)
  }

  const displayLang = (lang || 'TEXT').toUpperCase()
  const rawCode = encodeURIComponent(token.content)

  return `<div class="code-block-wrapper">` +
    `<div class="code-block-header">` +
      `<span class="code-dots"><i class="dot dot-red"></i><i class="dot dot-yellow"></i><i class="dot dot-green"></i></span>` +
      `<div class="code-header-right">` +
        `<span class="code-block-lang">${displayLang}</span>` +
        `<button type="button" class="code-copy-btn" data-code="${rawCode}" title="复制代码">复制</button>` +
      `</div>` +
    `</div>` +
    `<pre><code class="hljs ${lang ? 'language-' + md.utils.escapeHtml(lang) : ''}">${highlighted}</code></pre>` +
  `</div>\n`
}

// 仅处理 Markdown 正文 token；代码、链接、图片属性和转义示例不参与引用转换。
md.core.ruler.after('linkify', 'file_citations', (state) => {
  const lookup = state.env.citationLookup

  for (const block of state.tokens) {
    if (block.type !== 'inline' || !block.children) continue

    const references = new Map()
    let linkDepth = 0

    for (const token of block.children) {
      if (token.type === 'link_open') linkDepth++
      if (token.type === 'link_close') linkDepth--
      if (token.type !== 'text' || linkDepth !== 0) continue

      token.content = token.content.replace(/\[(C\d+)\]/g, (marker, id) => {
        const group = lookup.get(id)
        if (!group) return marker

        if (!references.has(group.key)) references.set(group.key, { number: group.number, ids: new Set() })
        references.get(group.key).ids.add(id)
        return ''
      })
    }

    for (const reference of references.values()) {
      const space = new state.Token('text', '', 0)
      space.content = ' '
      const badge = new state.Token('file_citation', 'button', 0)
      badge.meta = { number: reference.number, ids: [...reference.ids] }
      block.children.push(space, badge)
    }
  }
})

md.renderer.rules.file_citation = (tokens, index) => {
  const { number, ids } = tokens[index].meta
  const sourceIds = md.utils.escapeHtml(ids.join(','))
  return `<button type="button" class="inline-citation-badge" data-citation-ids="${sourceIds}" data-citation-number="${number}" aria-label="查看来源 ${number} 的本段摘录" title="查看本段来源摘录">[${number}]</button>`
}

// 调用方继续使用 DOMPurify 清洗；此处返回的 HTML 仅用于展示，不改动原始答案。
export function renderCitationMarkdown(raw, citations = []) {
  if (!raw || typeof raw !== 'string') return ''

  const citationLookup = new Map()
  for (const group of groupAgentCitations(citations)) {
    for (const id of group.sourceIds) citationLookup.set(id, group)
  }

  return md.render(raw, { citationLookup })
}
