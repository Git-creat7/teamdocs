package asia.creat.service;

public interface DocumentSummaryService {
    /**
     * 为单个已就绪文档生成并保存 AI 模型摘要
     * @param documentId 文档 ID
     * @return true 若模型摘要成功生成并写入，false 则保留原有规则摘要
     */
    boolean processDocumentSummary(Long documentId);

    /**
     * 重置超时的摘要任务
     * @return 重置的任务数
     */
    int resetTimeoutTasks();
}
