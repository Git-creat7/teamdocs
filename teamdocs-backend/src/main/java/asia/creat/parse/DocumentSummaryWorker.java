package asia.creat.parse;

import asia.creat.config.ParseProperties;
import asia.creat.entity.Document;
import asia.creat.mapper.DocumentMapper;
import asia.creat.service.DocumentSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "teamdocs.parse", name = {"enabled", "summary-enabled"}, havingValue = "true", matchIfMissing = true)
public class DocumentSummaryWorker {

    private final DocumentMapper documentMapper;
    private final DocumentSummaryService documentSummaryService;
    private final ParseProperties properties;

    @Scheduled(fixedDelayString = "${teamdocs.parse.scan-delay-ms:5000}")
    public void scanPending() {
        if (!properties.isEnabled() || !properties.isSummaryEnabled()) {
            return;
        }

        try {
            long nowMs = System.currentTimeMillis();
            List<Document> pending = documentMapper.findPendingSummaryDocuments(
                    Math.max(1, properties.getSummaryBatchSize()), nowMs);

            if (pending == null || pending.isEmpty()) {
                return;
            }

            for (Document doc : pending) {
                documentSummaryService.processDocumentSummary(doc.getId());
            }
        } catch (Exception e) {
            log.warn("扫描待处理摘要任务异常: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void resetTimeoutTasks() {
        if (!properties.isEnabled() || !properties.isSummaryEnabled()) {
            return;
        }

        try {
            documentSummaryService.resetTimeoutTasks();
        } catch (Exception e) {
            log.warn("重置超时摘要任务异常: {}", e.getMessage());
        }
    }
}
