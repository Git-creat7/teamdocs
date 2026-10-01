const actions = {
  search_documents: {
    name: '文档查找',
    running: '正在查找相关文档…',
    finished: '已完成文档查找',
    empty: '未找到匹配的文档'
  },
  search_document_chunks: {
    name: '正文检索',
    running: '正在检索相关内容…',
    finished: '已完成相关内容检索',
    empty: '未找到匹配的正文内容'
  },
  read_document_chunks: {
    name: '正文读取',
    running: '正在阅读文档内容…',
    finished: '已完成正文读取',
    empty: '未读取到可用正文'
  }
}

const fallback = {
  name: '资料处理',
  running: '正在处理资料…',
  finished: '资料处理已完成',
  empty: '本次处理未返回资料'
}

export function getAgentProgress(run) {
  const active = !run || run.status === 'QUEUED' || run.status === 'RUNNING'
  const tools = run?.tools || []

  const steps = tools.map((tool) => {
    const action = Object.prototype.hasOwnProperty.call(actions, tool.toolName) ? actions[tool.toolName] : fallback
    const match = /^records=(\d+)$/.exec(tool.resultSummary || '')
    const resultCount = match ? Number(match[1]) : null
    let state
    let label

    if (tool.status === 'SUCCEEDED') {
      state = 'succeeded'
      label = resultCount === 0 ? action.empty : action.finished
    } else if (tool.status === 'RUNNING' && active) {
      state = 'running'
      label = action.running
    } else {
      state = run?.status === 'CANCELLED' ? 'stopped' : 'failed'
      label = action.name + (state === 'stopped' ? '已停止' : '未完成')
    }

    return { sequence: tool.sequence, state, label, resultCount, durationMs: tool.durationMs }
  })

  let label
  if (!run) label = '正在准备回复…'
  else if (run.status === 'QUEUED') label = '正在排队等待处理…'
  else if (run.status === 'RUNNING') {
    const current = [...steps].reverse().find((step) => step.state === 'running')
    label = current?.label || (steps.length ? '等待后续响应…'
      : run.modelCalls > 0 ? '等待模型响应…' : '正在准备模型请求…')
  } else {
    label = {
      SUCCEEDED: '处理完成',
      CANCELLED: '已停止',
      FAILED: '本次处理未完成',
      TIMED_OUT: '处理超时'
    }[run.status] || '正在确认处理状态…'
  }

  let waitingMessage = ''
  if (!run) waitingMessage = '正在确认运行状态，暂未收到处理记录。'
  else if (run.status === 'QUEUED') waitingMessage = '请求已进入队列，尚未开始执行。'
  else if (run.status === 'RUNNING' && !steps.some((step) => step.state === 'running')) {
    if (steps.length) waitingMessage = '本轮工具已返回，正在等待后续处理结果。'
    else if (run.modelCalls > 0) waitingMessage = '模型尚未返回内容，暂时没有可展示的处理步骤。'
    else waitingMessage = '运行已开始，正在准备模型请求。'
  }

  const hasDetails = active || steps.length > 0 || Boolean(run?.errorCode)

  return {
    visible: active || hasDetails || run?.status !== 'SUCCEEDED',
    active,
    hasDetails,
    label,
    waitingMessage,
    steps,
    emptyRead: tools.some((tool) => tool.toolName === 'read_document_chunks'
      && tool.status === 'SUCCEEDED' && tool.resultSummary === 'records=0')
  }
}
