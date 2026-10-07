package asia.creat.teamdocsbackend.service;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.config.EmbeddingProperties;
import asia.creat.config.MilvusProperties;
import asia.creat.entity.*;
import asia.creat.mapper.*;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DocumentIndexServiceTest {
    private final DocumentMapper docs = mock(DocumentMapper.class);
    private final DocumentContentMapper content = mock(DocumentContentMapper.class);
    private final ChunkIndex es = mock(ChunkIndex.class);
    private final VectorIndexMapper vectors = mock(VectorIndexMapper.class);
    private final DocumentIndexService service = new DocumentIndexService(docs, content, mock(SpaceMemberMapper.class), mock(SpaceMapper.class), es, vectors,
            Optional.empty(), new EmbeddingProperties(), new MilvusProperties());
    private final LoginUser user = new LoginUser(7L, "test");

    @BeforeEach void init() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DocumentContent.class);
    }
    @AfterEach void cleanup() { SpaceContext.clear(); }

    @Test void onlyOwnerAndAdminMayRepairButMembersMayView() throws Exception {
        var repair = DocumentIndexService.class.getMethod("repair", Long.class, Long.class, String.class, LoginUser.class);
        assertEquals(Set.of(SpaceRole.OWNER, SpaceRole.ADMIN), Set.of(repair.getAnnotation(RequireSpaceRole.class).value()));
        var status = DocumentIndexService.class.getMethod("status", Long.class, Long.class, LoginUser.class);
        assertTrue(Set.of(status.getAnnotation(RequireSpaceRole.class).value()).contains(SpaceRole.MEMBER));
        Document doc = Document.builder().id(10L).spaceId(1L).parseStatus(ParseStatus.READY).parseVersion(2).build();
        when(docs.selectById(10L)).thenReturn(doc);
        when(content.selectCount(any())).thenReturn(2L);
        when(es.documentStatus(1L, 10L, 2, 2)).thenReturn(new ChunkIndex.DocumentStatus("MISSING", 0, "missing"));
        SpaceMember member = new SpaceMember(); member.setRole(SpaceRole.MEMBER); SpaceContext.set(member);
        assertFalse(service.status(1L, 10L, user).canRepair());
        verify(es, never()).syncDocument(anyLong());
    }

    @Test void rejectsForeignDocumentAndNeverCallsGlobalRebuild() {
        when(docs.selectById(10L)).thenReturn(Document.builder().id(10L).spaceId(2L).parseStatus(ParseStatus.READY).build());
        assertThrows(RuntimeException.class, () -> service.repair(1L, 10L, "es", user));
        verifyNoInteractions(es, vectors);
    }

    @Test void repairsOnlySpecifiedDocumentAndKeepsFailureRetryable() {
        when(docs.selectById(10L)).thenReturn(Document.builder().id(10L).spaceId(1L).parseStatus(ParseStatus.READY).parseVersion(2).build());
        when(es.enabled()).thenReturn(true);
        doThrow(new IllegalStateException("private backend details")).when(es).syncDocument(10L);
        var error = assertThrows(RuntimeException.class, () -> service.repair(1L, 10L, "es", user));
        assertFalse(error.getMessage().contains("private backend"));
        assertThrows(RuntimeException.class, () -> service.repair(1L, 10L, "es", user));
        verify(es, times(2)).syncDocument(10L);
        verify(es, never()).rebuild();
    }
}
