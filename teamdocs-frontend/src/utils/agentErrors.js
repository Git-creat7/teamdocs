const labels = {
  AI_NOT_AUTHORIZED: '文档问答尚未启用，请联系管理员确认文档出站授权。',
  AI_MODEL_NOT_CONFIGURED: '尚未配置模型名称，请联系管理员。',
  MODEL_CALL_LIMIT: '已达到本次处理上限，请缩小问题范围后再问。',
  TOOL_CALL_LIMIT: '已达到本次检索上限，可根据已有来源继续提问。',
  CONTEXT_LIMIT: '本次资料超出处理上限，请指定文档或缩小问题范围。',
  SOURCE_CHANGED: '资料已更新，本次回答未发布，请重新提问。',
  ACCESS_REVOKED: '当前空间或资料已不可访问。',
  ANSWER_UNVERIFIABLE: '无法确认回答的来源，本次未展示答案。可以换一种问法。',
  RUN_TIMEOUT: '处理超时，已停止后续执行。可以缩小问题范围后再问。',
  PROCESS_INTERRUPTED: '服务重启中断了本次处理，不会自动重复调用模型。',
  MODEL_FAILED: '模型服务暂时不可用，请稍后重试。',
  QUEUE_FULL: '当前处理任务较多，请稍后再试。',
  MODEL_CAPACITY_EXCEEDED: '模型通道繁忙，请稍后再试。',
  USER_CANCELLED: '已停止后续执行，已消耗的模型用量不能撤销。'
}

export function agentError(error) {
  const message = typeof error === 'string' ? error : error?.message || ''
  return Object.entries(labels).find(([key]) => message.includes(key))?.[1] || message || '暂时无法完成操作，请重试。'
}

export function accessLost(error) {
  const message = error?.message || String(error || '')
  return error?.status === 401 || error?.response?.status === 401 || /ACCESS_REVOKED|不是该空间成员|空间不存在|会话不存在|运行不存在/.test(message)
}
