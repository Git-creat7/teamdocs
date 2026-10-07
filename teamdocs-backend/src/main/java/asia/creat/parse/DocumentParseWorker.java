package asia.creat.parse;

import asia.creat.config.ParseProperties;
import asia.creat.entity.Document;
import asia.creat.entity.ParseStatus;
import asia.creat.mapper.DocumentMapper;
import asia.creat.service.DocumentParseService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;

import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaQueryChain;
import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaUpdateChain;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "teamdocs.parse", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentParseWorker {
    private final DocumentMapper documentMapper;
    private final DocumentParseService documentParseService;
    private final ParseProperties properties;
    private final Executor documentParseExecutor;

    // 保留原件用于缓存泄漏修复实验；限制总字节权重，不是 JVM 峰值内存上限。
    public static final Cache<String, byte[]> SOURCE_CACHE = newSourceCache(Ticker.systemTicker());

    static Cache<String, byte[]> newSourceCache(Ticker ticker) {
        return Caffeine.newBuilder()
                .maximumWeight(32L * 1024 * 1024)
                .weigher((String key, byte[] value) -> Math.max(1, value.length))
                .expireAfterWrite(Duration.ofMinutes(10))
                .ticker(ticker)
                .build();
    }

    public DocumentParseWorker(DocumentMapper documentMapper,
                               DocumentParseService documentParseService,
                               ParseProperties properties,
                               @Qualifier("documentParseExecutor") Executor documentParseExecutor) {
        this.documentMapper = documentMapper;
        this.documentParseService = documentParseService;
        this.properties = properties;
        this.documentParseExecutor = documentParseExecutor;
    }

    @Scheduled(fixedDelayString = "${teamdocs.parse.scan-delay-ms:5000}")
    public void scanPending() {
        Page<Document> page = lambdaQueryChain(documentMapper)
                .select(Document::getId)
                .eq(Document::getParseStatus, ParseStatus.PENDING)
                .exists("SELECT 1 FROM space s WHERE s.id = document.space_id AND s.deleted = 0")
                .orderByAsc(Document::getId).page(new Page<>(1, Math.max(1, properties.getBatchSize()), false));

        if (page == null || page.getRecords() == null || page.getRecords().isEmpty()) {
            return;
        }

        for (Document document : page.getRecords()) {
            Long id = document.getId();

            documentParseExecutor.execute(() -> documentParseService.parseDocument(id));
        }
    }

    @Scheduled(fixedDelayString = "${teamdocs.parse.scan-delay-ms:5000}")
    public void failTimedOut() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(Math.max(1, properties.getTimeoutSeconds()));
        Page<Document> page = lambdaQueryChain(documentMapper)
                .select(Document::getId, Document::getParseVersion)
                .eq(Document::getParseStatus, ParseStatus.PARSING)
                .isNotNull(Document::getParseStartedAt)
                .lt(Document::getParseStartedAt, deadline)
                .orderByAsc(Document::getId).page(new Page<>(1, Math.max(1, properties.getBatchSize()), false));
        int updated = 0;

        for (Document document : page.getRecords()) {
            if (lambdaUpdateChain(documentMapper)
                    .eq(Document::getId, document.getId())
                    .eq(Document::getParseVersion, document.getParseVersion())
                    .eq(Document::getParseStatus, ParseStatus.PARSING)
                    .lt(Document::getParseStartedAt, deadline)
                    .set(Document::getParseStatus, ParseStatus.FAILED)
                    .set(Document::getParseError, "解析超时")
                    .set(Document::getParsedAt, null)
                    .set(Document::getChunkCount, 0)
                    .setSql("updated_at = updated_at").update()) {
                updated++;
            }
        }

        if (updated > 0) {
            log.warn("有 {} 个解析任务超时", updated);
        }
    }
}
