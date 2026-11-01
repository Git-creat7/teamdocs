package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.AgentData.Dependency;
import asia.creat.agent.AgentFailure;
import asia.creat.agent.AgentJson;
import asia.creat.agent.AgentTools;
import asia.creat.agent.AgentWorker;
import asia.creat.config.AgentProperties;
import asia.creat.entity.Document;
import asia.creat.entity.ParseStatus;
import asia.creat.mapper.CommentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.security.LoginUser;
import asia.creat.vo.CommentVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentCommentToolTest {
    private static final LoginUser USER = new LoginUser(7L, "reader");

    /** 评论按页返回，长内容截断，回复对象可空，且评论不进入来源列表。 */
    @Test
    @SuppressWarnings("unchecked")
    void readsCommentsWithPaginationAndTruncation() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        CommentMapper commentMapper = mock(CommentMapper.class);
        Document document = Document.builder().id(10L).spaceId(1L).name("说明.txt")
                .parseStatus(ParseStatus.READY).parseVersion(1).updatedAt(LocalDateTime.now()).build();
        when(documentMapper.selectById(10L)).thenReturn(document);

        CommentVO longComment = new CommentVO();
        longComment.setUserName("alice");
        longComment.setCreatedAt(LocalDateTime.of(2026, 11, 1, 15, 20));
        longComment.setContent("x".repeat(600));

        CommentVO reply = new CommentVO();
        reply.setUserName("bob");
        reply.setCreatedAt(LocalDateTime.of(2026, 11, 1, 16, 0));
        reply.setContent("我同意");
        reply.setReplyToUserName("alice");

        Page<CommentVO> page = new Page<>(1, 20);
        page.setRecords(List.of(longComment, reply));
        page.setTotal(21);
        when(commentMapper.listForAgent(any(), eq(10L))).thenReturn(page);

        AgentTools tools = tools(documentMapper, commentMapper, true);
        var state = new AgentTools.State();
        var result = tools.execute(1L, USER, request("{\"documentId\":10,\"page\":1}"), state);

        assertEquals(2, result.count());
        assertEquals(List.of(new Dependency(10L, null, null, null, null)), state.dependencies());
        assertTrue(state.sources().isEmpty());

        Map<String, Object> data = (Map<String, Object>) result.data();
        assertEquals(21L, data.get("total"));
        assertEquals(true, data.get("hasMore"));
        assertEquals(false, data.get("limitReached"));
        assertEquals(true, data.get("untrusted"));

        String payload = new AgentJson(new ObjectMapper()).write(data);

        assertTrue(payload.contains("\"type\":\"comment\""));
        assertTrue(payload.contains("\"author\":\"alice\""));
        assertTrue(payload.contains("\"createdAt\":\"2026-11-01 15:20\""));
        assertTrue(payload.contains("\"truncated\":true"));
        assertTrue(payload.contains("\"replyTo\":\"alice\""));
        assertTrue(payload.contains("\"replyTo\":null"));
    }

    /** 到达第 50 页时如实返回 hasMore，并提示翻页上限。 */
    @Test
    @SuppressWarnings("unchecked")
    void reportsLimitReachedAtLastAllowedPage() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        CommentMapper commentMapper = mock(CommentMapper.class);
        when(documentMapper.selectById(10L)).thenReturn(document());
        Page<CommentVO> page = new Page<>(50, 20);
        page.setRecords(List.of());
        page.setTotal(1001);
        when(commentMapper.listForAgent(any(), eq(10L))).thenReturn(page);

        var result = tools(documentMapper, commentMapper, true)
                .execute(1L, USER, request("{\"documentId\":10,\"page\":50}"), new AgentTools.State());

        Map<String, Object> data = (Map<String, Object>) result.data();
        assertEquals(true, data.get("hasMore"));
        assertEquals(true, data.get("limitReached"));
    }

    /** 默认关闭时不向模型暴露评论工具，也不能被直接调用。 */
    @Test
    void commentsToolIsHiddenAndRejectedWhenDisabled() {
        AgentTools tools = tools(mock(DocumentMapper.class), mock(CommentMapper.class), false);

        assertTrue(tools(mock(DocumentMapper.class), mock(CommentMapper.class), true).specifications().stream()
                .anyMatch(tool -> "read_document_comments".equals(tool.name())));
        assertFalse(tools.specifications().stream().anyMatch(tool -> "read_document_comments".equals(tool.name())));
        AgentFailure failure = assertThrows(AgentFailure.class,
                () -> tools.execute(1L, USER, request("{\"documentId\":10}"), new AgentTools.State()));
        assertEquals("TOOL_NOT_ALLOWED", failure.code());
    }

    /** 页码越界或携带白名单外参数时直接拒绝。 */
    @Test
    void rejectsInvalidPageAndUnknownArguments() {
        AgentTools tools = tools(mock(DocumentMapper.class), mock(CommentMapper.class), true);

        assertEquals("INVALID_ARGUMENT", assertThrows(AgentFailure.class,
                () -> tools.execute(1L, USER, request("{\"documentId\":10,\"page\":0}"), new AgentTools.State())).code());
        assertEquals("INVALID_ARGUMENT", assertThrows(AgentFailure.class,
                () -> tools.execute(1L, USER, request("{\"documentId\":10,\"page\":51}"), new AgentTools.State())).code());
        assertEquals("UNKNOWN_ARGUMENT", assertThrows(AgentFailure.class,
                () -> tools.execute(1L, USER, request("{\"documentId\":10,\"extra\":1}"), new AgentTools.State())).code());
    }

    /** 限定文档范围时，范围外的评论读取必须在查询前拒绝。 */
    @Test
    void rejectsDocumentOutsideScopeBeforeReadingComments() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        CommentMapper commentMapper = mock(CommentMapper.class);
        var state = new AgentTools.State();
        ReflectionTestUtils.setField(state, "scopeIds", List.of(99L));

        AgentFailure failure = assertThrows(AgentFailure.class,
                () -> tools(documentMapper, commentMapper, true)
                        .execute(1L, USER, request("{\"documentId\":10}"), state));

        assertEquals("DOCUMENT_OUTSIDE_SCOPE", failure.code());
        verifyNoInteractions(documentMapper, commentMapper);
    }

    /** 回收站文档由 @TableLogic 自动过滤，不能读取评论。 */
    @Test
    void rejectsTrashedDocument() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        CommentMapper commentMapper = mock(CommentMapper.class);
        when(documentMapper.selectById(10L)).thenReturn(null);

        AgentFailure failure = assertThrows(AgentFailure.class,
                () -> tools(documentMapper, commentMapper, true)
                        .execute(1L, USER, request("{\"documentId\":10}"), new AgentTools.State()));

        assertEquals("DOCUMENT_NOT_ACCESSIBLE", failure.code());
        verifyNoInteractions(commentMapper);
    }

    /** 只读评论、不读正文时，空 citations 能通过最终答案校验。 */
    @Test
    void commentOnlyAnswerAllowsEmptyCitations() {
        var worker = new AgentWorker(null, null, null, null, new AgentJson(new ObjectMapper()),
                null, null, null, null, null, null, false, null, null, null, null, null);

        assertNotNull(ReflectionTestUtils.invokeMethod(worker, "finalAnswer",
                "{\"answer\":\"评论中 alice 于 2026-11-01 15:20 提到需要补充示例。\",\"citations\":[]}",
                new AgentTools.State()));
    }

    /** 评论依赖只校验文档仍在当前空间（忽略解析版本变化），且与正文依赖共存时升级为完整依赖。 */
    @Test
    void commentDependencyTracksExistenceAndUpgradesCleanly() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        AgentTools tools = tools(documentMapper, mock(CommentMapper.class), true);

        var state = new AgentTools.State();
        state.dependComment(10L);
        assertEquals(List.of(new Dependency(10L, null, null, null, null)), state.dependencies());

        // 文档重新解析或状态流转不会让仅评论依赖失效
        when(documentMapper.selectById(10L)).thenReturn(Document.builder()
                .id(10L).spaceId(1L).name("说明.txt").parseStatus(ParseStatus.PARSING).parseVersion(9).build());
        assertFalse(tools.currentDependencies(1L, USER, state.dependencies()));

        // 文档进入回收站或被删除时，仅评论依赖失效（供历史消息遮罩与多轮过滤使用）
        when(documentMapper.selectById(10L)).thenReturn(null);
        assertTrue(tools.currentDependencies(1L, USER, state.dependencies()));

        // 先读评论再读正文（或先读正文再读评论）均保留完整依赖，不触发 SOURCE_CHANGED
        Dependency full = new Dependency(10L, 2, "READY", "说明.txt", LocalDateTime.of(2026, 11, 1, 12, 0));
        state.depend(full);
        state.dependComment(10L);
        assertEquals(List.of(full), state.dependencies());
    }

    private static AgentTools tools(DocumentMapper documentMapper, CommentMapper commentMapper, boolean commentsEnabled) {
        AgentProperties properties = new AgentProperties();
        properties.setCommentsEnabled(commentsEnabled);

        return new AgentTools(null, null, documentMapper, commentMapper, properties,
                new AgentJson(new ObjectMapper()), null);
    }

    private static ToolExecutionRequest request(String arguments) {
        return ToolExecutionRequest.builder().id("call-1").name("read_document_comments").arguments(arguments).build();
    }

    private static Document document() {
        return Document.builder().id(10L).spaceId(1L).name("说明.txt")
                .parseStatus(ParseStatus.READY).parseVersion(1).updatedAt(LocalDateTime.now()).build();
    }
}
