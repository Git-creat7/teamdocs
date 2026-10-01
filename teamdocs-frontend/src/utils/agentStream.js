export const isTerminalRun = (status) => !['QUEUED', 'RUNNING'].includes(status)

export function sameAgentContext(expected, actual) {
  return ['spaceId', 'sessionId', 'runId'].every((key) => String(expected[key]) === String(actual[key]))
}

export function isSendKey(event) {
  return event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229
}

export async function readAgentEvents(body, { scope, signal, onEvent }) {
  if (!body) throw new Error('浏览器无法读取实时响应')
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let sequence = 0
  const abort = () => { reader.cancel().catch(() => {}) }
  signal?.addEventListener('abort', abort, { once: true })
  try {
    while (true) {
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
      const { done, value } = await reader.read()
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      if (buffer.length > 262144) throw new Error('实时响应超过大小限制')
      let boundary
      while ((boundary = /\r\n\r\n|\n\n|\r\r/.exec(buffer))) {
        const frame = buffer.slice(0, boundary.index)
        buffer = buffer.slice(boundary.index + boundary[0].length)
        let type = 'message'
        const data = []
        for (const line of frame.split(/\r\n|\r|\n/)) {
          if (line.startsWith('event:')) type = line.slice(6).trim()
          if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
        }
        if (!data.length) continue
        const event = JSON.parse(data.join('\n'))
        if (!sameAgentContext(scope, event)) continue
        if (!Number.isSafeInteger(event.sequence) || event.sequence <= sequence) continue
        sequence = event.sequence
        if (onEvent({ ...event, type }) === false) return
      }
    }
  } finally {
    signal?.removeEventListener('abort', abort)
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

export function abortableDelay(ms, signal) {
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')) }
    const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve() }, ms)
    if (signal?.aborted) abort()
    else signal?.addEventListener('abort', abort, { once: true })
  })
}
