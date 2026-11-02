package asia.creat.service.impl;

import asia.creat.agent.AgentWorker;
import asia.creat.config.AgentProperties;
import asia.creat.config.ParseProperties;
import asia.creat.entity.Document;
import asia.creat.entity.DocumentContent;
import asia.creat.entity.ParseStatus;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.service.DocumentSummaryService;
import asia.creat.utils.CacheClient;
import asia.creat.utils.RedisConstants;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class DocumentSummaryServiceImpl implements DocumentSummaryService {

    private final DocumentMapper documentMapper;
    private final DocumentContentMapper documentContentMapper;
    private final ParseProperties parseProperties;
    private final ObjectProvider<ChatLanguageModel> modelProvider;
    private final CacheClient cacheClient;
    private final ObjectProvider<AgentWorker> agentWorkerProvider;
    private final ObjectProvider<AgentProperties> agentPropertiesProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public DocumentSummaryServiceImpl(
            DocumentMapper documentMapper,
            DocumentContentMapper documentContentMapper,
            ParseProperties parseProperties,
            ObjectProvider<ChatLanguageModel> modelProvider,
            CacheClient cacheClient,
            ObjectProvider<AgentWorker> agentWorkerProvider,
            ObjectProvider<AgentProperties> agentPropertiesProvider) {
        this.documentMapper = documentMapper;
        this.documentContentMapper = documentContentMapper;
        this.parseProperties = parseProperties;
        this.modelProvider = modelProvider;
        this.cacheClient = cacheClient;
        this.agentWorkerProvider = agentWorkerProvider;
        this.agentPropertiesProvider = agentPropertiesProvider;
    }

    public DocumentSummaryServiceImpl(
            DocumentMapper documentMapper,
            DocumentContentMapper documentContentMapper,
            ParseProperties parseProperties,
            ObjectProvider<ChatLanguageModel> modelProvider,
            CacheClient cacheClient,
            ObjectProvider<AgentWorker> agentWorkerProvider) {
        this(documentMapper, documentContentMapper, parseProperties, modelProvider, cacheClient, agentWorkerProvider, null);
    }

    @Override
    public boolean processDocumentSummary(Long documentId) {
        if (!parseProperties.isEnabled() || !parseProperties.isSummaryEnabled()) {
            return false;
        }

        AgentProperties agentProperties = agentPropertiesProvider != null ? agentPropertiesProvider.getIfAvailable() : null;
        if (agentProperties != null && (!agentProperties.isEnabled() || !agentProperties.isAllowDocumentEgress())) {
            log.debug("未授权文档出站或未启用AI，保留规则摘要: documentId={}", documentId);
            return false;
        }

        Document doc = documentMapper.selectById(documentId);
        if (doc == null || !Integer.valueOf(0).equals(doc.getDeleted()) || doc.getParseStatus() != ParseStatus.READY) {
            return false;
        }

        ChatLanguageModel model = modelProvider.getIfAvailable();
        if (model == null) {
            log.debug("未配置语言模型，保留规则摘要: documentId={}", documentId);
            return false;
        }

        String workerId = UUID.randomUUID().toString();
        long nowMs = System.currentTimeMillis();
        long leaseUntil = nowMs + Math.max(10, parseProperties.getSummaryTimeoutSeconds()) * 1000L;

        int claimed = documentMapper.claimSummaryTask(documentId, doc.getParseVersion(), workerId, leaseUntil, nowMs);
        if (claimed <= 0) {
            return false;
        }

        try {
            List<DocumentContent> chunks = documentContentMapper.selectList(
                    new LambdaQueryWrapper<DocumentContent>()
                            .eq(DocumentContent::getDocumentId, documentId)
                            .orderByAsc(DocumentContent::getChunkIndex)
            );

            if (chunks == null || chunks.isEmpty()) {
                documentMapper.completeSummary(documentId, doc.getParseVersion(),
                        doc.getSummary(), null, "RULE", "FAILED", "文档切片为空", workerId);
                return false;
            }

            StringBuilder textBuilder = new StringBuilder();
            int maxChars = parseProperties.getSummaryMaxChars();
            for (DocumentContent chunk : chunks) {
                if (chunk.getContent() != null) {
                    textBuilder.append(chunk.getContent()).append("\n");
                    if (textBuilder.length() >= maxChars) {
                        break;
                    }
                }
            }

            String text = textBuilder.toString();
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars);
            }

            String prompt = String.format("""
                    你是一个专业的文档摘要提取助手。请根据以下文档内容，提取一段简明扼要的摘要（不超过 200 字），并提取 3-5 个核心关键词。
                    必须且仅以严格的 JSON 格式输出，不要附加任何 Markdown 标记或多余文字。
                    输出格式示例：
                    {"summary": "文档的核心总结...", "keywords": ["关键词1", "关键词2", "关键词3"]}

                    文档内容如下：
                    %s
                    """, text);

            String response = model.generate(List.of(
                    SystemMessage.from("你是一个专业的文档助手，只能输出合法的JSON。"),
                    UserMessage.from(prompt)
            )).content().text();

            String summaryText = null;
            String keywordsText = null;

            try {
                String cleanJson = response.trim();
                if (cleanJson.startsWith("```json")) {
                    cleanJson = cleanJson.substring(7);
                } else if (cleanJson.startsWith("```")) {
                    cleanJson = cleanJson.substring(3);
                }
                if (cleanJson.endsWith("```")) {
                    cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
                }
                cleanJson = cleanJson.trim();

                JsonNode node = objectMapper.readTree(cleanJson);
                if (node.has("summary") && !node.get("summary").isNull()) {
                    summaryText = node.get("summary").asText().trim();
                }
                if (node.has("keywords") && node.get("keywords").isArray()) {
                    List<String> kwList = new ArrayList<>();
                    for (JsonNode kw : node.get("keywords")) {
                        if (!kw.isNull() && !kw.asText().isBlank()) {
                            kwList.add(kw.asText().trim());
                        }
                    }
                    if (!kwList.isEmpty()) {
                        keywordsText = String.join(",", kwList);
                        if (keywordsText.length() > 500) {
                            keywordsText = keywordsText.substring(0, 500);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("文档 {} 模型返回格式非标准JSON: {}", documentId, response);
                if (response != null && !response.isBlank()) {
                    summaryText = response.length() > 200 ? response.substring(0, 200) : response;
                }
            }

            if (summaryText == null || summaryText.isBlank()) {
                documentMapper.completeSummary(documentId, doc.getParseVersion(),
                        doc.getSummary(), null, "RULE", "FAILED", "模型返回摘要为空", workerId);
                return false;
            }

            int updated = documentMapper.completeSummary(documentId, doc.getParseVersion(),
                    summaryText, keywordsText, "MODEL", "SUCCEEDED", null, workerId);

            if (updated > 0) {
                log.info("文档 {} 成功生成并更新模型摘要", documentId);
                invalidateCache(doc.getSpaceId());
                return true;
            } else {
                log.info("文档 {} 写入模型摘要时版本失效或租约已过期", documentId);
                return false;
            }

        } catch (Exception e) {
            log.warn("文档 {} 生成模型摘要异常: {}", documentId, e.getMessage());
            documentMapper.completeSummary(documentId, doc.getParseVersion(),
                    doc.getSummary(), null, "RULE", "FAILED", truncate(e.getMessage()), workerId);
            return false;
        }
    }

    @Override
    public int resetTimeoutTasks() {
        return documentMapper.resetTimeoutSummaryTasks(System.currentTimeMillis());
    }

    private void invalidateCache(Long spaceId) {
        if (spaceId != null) {
            try {
                cacheClient.delete(RedisConstants.CACHE_SPACE_PREFIX + spaceId);
            } catch (Exception ignored) {
            }
            AgentWorker agentWorker = agentWorkerProvider.getIfAvailable();
            if (agentWorker != null) {
                agentWorker.invalidateSpaceCache(spaceId);
            }
        }
    }

    private String truncate(String text) {
        if (text == null) return null;
        return text.length() > 250 ? text.substring(0, 250) : text;
    }
}
