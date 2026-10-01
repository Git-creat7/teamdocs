package asia.creat.agent;

import asia.creat.agent.AgentData.*;
import asia.creat.agent.mcp.McpHttpClient;
import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.dto.PageQuery;
import asia.creat.entity.Document;
import asia.creat.mapper.DocumentMapper;
import asia.creat.retrieval.RetrievalContext;
import asia.creat.security.LoginUser;
import asia.creat.service.DocumentService;
import asia.creat.service.DocumentChunkQueryService;
import asia.creat.vo.ChunkHitVO;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Component
public class AgentTools {
    private final DocumentService documents;
    private final DocumentChunkQueryService chunks;
    private final DocumentMapper documentMapper;
    private final AgentJson json;
    private final McpHttpClient mcpHttpClient;
    public static final Set<String> NAMES = Set.of("search_documents", "search_document_chunks", "read_document_chunks");

    public AgentTools(
            DocumentService documents,
            DocumentChunkQueryService chunks,
            DocumentMapper documentMapper,
            AgentJson json,
            @Autowired(required = false) McpHttpClient mcpHttpClient) {
        this.documents = documents;
        this.chunks = chunks;
        this.documentMapper = documentMapper;
        this.json = json;
        this.mcpHttpClient = mcpHttpClient;
    }

    public static final List<ToolSpecification> SPECS = List.of(
            ToolSpecification.builder().name("search_documents").description("搜索当前空间文档名和标签。只返回元数据，不代表已经阅读正文。")
                    .parameters(JsonObjectSchema.builder().addStringProperty("keyword").addIntegerProperty("page")
                            .required("keyword").build()).build(),
            ToolSpecification.builder().name("search_document_chunks").description("在当前空间检索正文。可指定 documentId 限定文档。返回可引用的 sourceId。")
                    .parameters(JsonObjectSchema.builder().addStringProperty("keyword").addIntegerProperty("documentId")
                            .required("keyword").build()).build(),
            ToolSpecification.builder().name("read_document_chunks").description("按分块顺序读取文档。startIndex 从 0 开始，limit 为 1 到 20；hasMore=true 表示尚未读完。")
                    .parameters(JsonObjectSchema.builder().addIntegerProperty("documentId").addIntegerProperty("startIndex")
                            .addIntegerProperty("limit").required("documentId").build()).build());

    public static List<ToolSpecification> defaultSpecifications() {
        return SPECS;
    }

    public List<ToolSpecification> specifications() {
        List<ToolSpecification> all = new ArrayList<>(SPECS);
        if (mcpHttpClient != null) {
            all.addAll(mcpHttpClient.getToolSpecifications());
        }
        return all;
    }

    public boolean isMcpTool(String name) {
        return mcpHttpClient != null && mcpHttpClient.hasTool(name);
    }

    public static class State {
        long deadlineMs = Long.MAX_VALUE;
        Runnable checkpoint = () -> { };
        final Map<Long, Dependency> dependencies = new LinkedHashMap<>();
        final Map<String, Source> sources = new LinkedHashMap<>();
        public void depend(Dependency dependency) {
            if (!dependencies.containsKey(dependency.documentId()) && dependencies.size() >= 128) throw new AgentFailure("DEPENDENCY_LIMIT");
            Dependency previous = dependencies.putIfAbsent(dependency.documentId(), dependency);
            if (previous != null && !previous.equals(dependency)) throw new AgentFailure("SOURCE_CHANGED");
        }
        public List<Dependency> dependencies() { return List.copyOf(dependencies.values()); }
        public List<Source> sources() { return List.copyOf(sources.values()); }
    }
    public record Result(Object data, int count) { }
    private record FoundDocument(Long documentId, String name, String fileType, String parseStatus, Integer parseVersion) { }
    private record Evidence(String sourceId, Long documentId, Long chunkId, Integer chunkIndex, String documentName, Integer parseVersion,
                            Integer pageNumber, Integer charStart, Integer charEnd, String excerpt) { }
    private record ReadResult(String parseStatus, boolean available, boolean hasMore, List<Evidence> chunks) { }

    @RequireSpaceRole
    public Result execute(@SpaceId Long spaceId, LoginUser user, ToolExecutionRequest request, State state) {
        if (isMcpTool(request.name())) {
            log.info("命中外部 MCP 工具，转发执行: name={}", request.name());
            String result = mcpHttpClient.callTool(request.name(), request.arguments());
            return new Result(Map.of("mcpResult", result), 1);
        }


        if (!NAMES.contains(request.name())) throw new AgentFailure("TOOL_NOT_ALLOWED");
        Set<String> fields = switch (request.name()) {
            case "search_documents" -> Set.of("keyword", "page");
            case "search_document_chunks" -> Set.of("keyword", "documentId");
            default -> Set.of("documentId", "startIndex", "limit");
        };
        JsonNode args = json.object(request.arguments(), fields);
        log.info("Agent 工具开始执行: toolName={}, spaceId={}, arguments={}", request.name(), spaceId, request.arguments());
        if ("search_documents".equals(request.name())) {
            PageQuery page = new PageQuery();
            page.setCurrent(integer(args, "page", 1, 1, 100)); page.setSize(6);
            log.info("Agent 工具 [search_documents] 执行: spaceId={}, keyword={}, page={}", spaceId, keyword(args), page.getCurrent());
            var found = documents.searchDocuments(spaceId, keyword(args), page, user);
            List<FoundDocument> results = new ArrayList<>();
            for (Document document : found.getRecords()) {
                Document current = current(spaceId, document.getId());
                Dependency dependency = dependency(current);
                if (!dependency.equals(dependency(document))) throw new AgentFailure("SOURCE_CHANGED");
                state.depend(dependency);
                results.add(new FoundDocument(current.getId(), current.getName(), current.getFileType(),
                        current.getParseStatus().name(), current.getParseVersion()));
            }
            log.info("Agent 工具 [search_documents] 完成: 找到 {} 篇文档", results.size());
            return new Result(Map.of("documents", results, "page", page.getCurrent(), "hasMore", page.getCurrent() < found.getPages()), results.size());
        }
        if ("search_document_chunks".equals(request.name())) {
            Long documentId = args.has("documentId") ? integer(args, "documentId", -1, 1, Long.MAX_VALUE) : null;
            if (documentId != null) current(spaceId, documentId);
            log.info("Agent 工具 [search_document_chunks] 执行: spaceId={}, keyword={}, documentId={}", spaceId, keyword(args), documentId);
            List<ChunkHitVO> hits;
            try (RetrievalContext ignored = RetrievalContext.open(state.deadlineMs, state.checkpoint,
                    hit -> recordRetrievalDependency(spaceId, hit, state))) {
                hits = documentId == null ? chunks.searchChunks(spaceId, keyword(args), user)
                        : chunks.searchChunksInDocument(spaceId, documentId, keyword(args), user);
            }
            List<Evidence> evidence = evidence(spaceId, hits, state);
            log.info("Agent 工具 [search_document_chunks] 完成: 命中 {} 个切片", hits.size());
            return new Result(Map.of("chunks", evidence), evidence.size());
        }
        long documentId = integer(args, "documentId", -1, 1, Long.MAX_VALUE);
        int start = (int) integer(args, "startIndex", 0, 0, 100000);
        int limit = (int) integer(args, "limit", 6, 1, 20);
        current(spaceId, documentId);
        log.info("Agent 工具 [read_document_chunks] 执行: spaceId={}, documentId={}, start={}, limit={}", spaceId, documentId, start, limit);
        var page = chunks.readChunks(spaceId, documentId, start, limit, user);
        state.depend(dependency(current(spaceId, documentId)));
        List<Evidence> evidence = evidence(spaceId, page.getChunks(), state);
        log.info("Agent 工具 [read_document_chunks] 完成: 返回 {} 个切片, hasMore={}", evidence.size(), page.isHasMore());
        return new Result(new ReadResult(page.getParseStatus().name(), page.isAvailable(), page.isHasMore(), evidence), evidence.size());
    }

    @RequireSpaceRole
    public boolean currentDependencies(@SpaceId Long spaceId, LoginUser user, List<Dependency> dependencies) {
        for (Dependency expected : dependencies) {
            Document document = documentMapper.selectById(expected.documentId());
            if (document == null || !spaceId.equals(document.getSpaceId()) || !expected.equals(dependency(document))) return true;
        }
        return false;
    }

    @RequireSpaceRole
    public List<Citation> citations(@SpaceId Long spaceId, LoginUser user, List<Source> sources) {
        List<Citation> result = new ArrayList<>();
        for (Source source : sources) {
            var resolved = chunks.resolveCitation(spaceId, source.documentId(), source.chunkId(), source.parseVersion(), user);
            if (!resolved.isAccessible()) throw new AgentFailure("SOURCE_CHANGED");
            ChunkHitVO hit = resolved.getChunk();
            String excerpt = shorten(hit.getExcerpt(), 200);
            Integer end = hit.getCharStart() == null ? hit.getCharEnd() : hit.getCharStart() + excerpt.length();
            result.add(new Citation(source.id(), source.documentId(), source.chunkId(), hit.getChunkIndex(), source.parseVersion(), hit.getDocumentName(),
                    hit.getPageNumber(), hit.getCharStart(), end, excerpt, "/preview/" + spaceId + "/" + source.documentId()));
        }
        return result;
    }

    /**
     * 保存发送给重排模型的候选依赖，包括未进入最终答案的片段。
     * @param spaceId 当前空间
     * @param hit 已复核片段
     * @param state 本轮工具状态
     */
    private void recordRetrievalDependency(Long spaceId, ChunkHitVO hit, State state) {
        Document document = current(spaceId, hit.getDocumentId());
        if (!Objects.equals(document.getParseVersion(), hit.getParseVersion())
                || document.getParseStatus() != asia.creat.entity.ParseStatus.READY
                || !Objects.equals(document.getName(), hit.getDocumentName())) {
            throw new AgentFailure("SOURCE_CHANGED");
        }
        state.depend(dependency(document));
    }

    private List<Evidence> evidence(Long spaceId, List<ChunkHitVO> hits, State state) {
        List<Evidence> result = new ArrayList<>();
        for (ChunkHitVO hit : hits) {
            Document document = current(spaceId, hit.getDocumentId());
            if (!Objects.equals(document.getParseVersion(), hit.getParseVersion()) || !"READY".equals(document.getParseStatus().name()))
                throw new AgentFailure("SOURCE_CHANGED");
            state.depend(dependency(document));
            String key = hit.getChunkId() + ":" + hit.getParseVersion();
            Source source = state.sources.get(key);
            if (source == null) {
                if (state.sources.size() >= 24) throw new AgentFailure("SOURCE_LIMIT");
                source = new Source("C" + (state.sources.size() + 1), hit.getDocumentId(), hit.getChunkId(), hit.getParseVersion());
                state.sources.put(key, source);
            }
            result.add(new Evidence(source.id(), hit.getDocumentId(), hit.getChunkId(), hit.getChunkIndex(), hit.getDocumentName(), hit.getParseVersion(),
                    hit.getPageNumber(), hit.getCharStart(), hit.getCharEnd(), hit.getExcerpt()));
        }
        return result;
    }

    private Document current(Long spaceId, Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null || !spaceId.equals(document.getSpaceId())) throw new AgentFailure("DOCUMENT_NOT_ACCESSIBLE");
        return document;
    }
    private Dependency dependency(Document document) {
        return new Dependency(document.getId(), document.getParseVersion(), document.getParseStatus().name(), document.getName(), document.getUpdatedAt());
    }
    private String keyword(JsonNode args) {
        JsonNode node = args.get("keyword");
        if (node == null || !node.isTextual() || node.asText().isBlank() || node.asText().length() > 200)
            throw new AgentFailure("INVALID_ARGUMENT");
        return node.asText().trim();
    }
    private long integer(JsonNode args, String name, long fallback, long min, long max) {
        JsonNode node = args.get(name);
        long value = fallback;
        if (node != null) {
            if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new AgentFailure("INVALID_ARGUMENT");
            value = node.longValue();
        }
        if (value < min || value > max) throw new AgentFailure("INVALID_ARGUMENT");
        return value;
    }
    public static String shorten(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end);
    }
}
