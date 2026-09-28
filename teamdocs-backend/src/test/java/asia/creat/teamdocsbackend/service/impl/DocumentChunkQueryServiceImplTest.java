package asia.creat.teamdocsbackend.service.impl;

import asia.creat.common.exception.BusinessException;
import asia.creat.config.RetrievalProperties;
import asia.creat.entity.Document;
import asia.creat.entity.ParseStatus;
import asia.creat.entity.Space;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.SpaceMapper;
import asia.creat.security.LoginUser;
import asia.creat.service.impl.DocumentChunkQueryServiceImpl;
import asia.creat.service.ChunkIndex;
import asia.creat.vo.ChunkIndexHit;
import asia.creat.vo.ChunkCitationVO;
import asia.creat.vo.ChunkHitVO;
import asia.creat.vo.ChunkReadVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentChunkQueryServiceImplTest {
    private static final LoginUser USER = new LoginUser(7L, "alice");

    @Mock
    private DocumentContentMapper documentContentMapper;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private SpaceMapper spaceMapper;

    @Mock
    private ChunkIndex chunkIndex;

    private DocumentChunkQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        RetrievalProperties properties = new RetrievalProperties();
        properties.setSearchLimit(6);
        properties.setReadLimit(20);
        properties.setMaxChars(8);
        service = new DocumentChunkQueryServiceImpl(documentContentMapper, documentMapper, spaceMapper, properties, chunkIndex);
    }

    @Test
    void blankKeywordIsRejected() {
        assertThrows(BusinessException.class, () -> service.searchChunks(1L, "  ", USER));
        verify(documentContentMapper, never()).searchChunks(any(), any(), anyInt());
    }

    @Test
    void singleCharacterDoesNotHitFulltext() {
        assertThrows(BusinessException.class, () -> service.searchChunks(1L, "备", USER));
        assertThrows(BusinessException.class, () -> service.searchChunks(1L, "😀", USER));
        verify(documentContentMapper, never()).searchChunks(any(), any(), anyInt());
    }

    @Test
    void searchUsesPhraseAndConfiguredLimit() {
        when(documentContentMapper.searchChunks(1L, "\"上线\" \"检查\"", 6)).thenReturn(List.of(hit("上线检查清单")));

        List<ChunkHitVO> hits = service.searchChunks(1L, "上线+检查", USER);

        assertEquals(1, hits.size());
        assertEquals("上线检查清单", hits.get(0).getExcerpt());
    }

    @Test
    void spaceSeparatedTermsAreSearchedSeparatelyAndSingleCharactersDropped() {
        service.searchChunks(1L, " 数据库  备份 A 😀", USER);

        verify(documentContentMapper).searchChunks(1L, "\"数据库\" \"备份\"", 6);
    }

    @Test
    void searchTruncatesToCharBudget() {
        when(documentContentMapper.searchChunks(eq(1L), eq("\"上线检查\""), eq(6)))
                .thenReturn(List.of(hit("123456789"), hit("abcdef")));

        List<ChunkHitVO> hits = service.searchChunks(1L, "上线检查", USER);

        assertEquals(1, hits.size());
        assertEquals("12345678", hits.get(0).getExcerpt());
    }

    @Test
    void shortenedSearchExcerptPreservesUnicodeAndReportsActualOffsets() {
        ChunkHitVO source = hit("1234567😀x");
        source.setCharStart(100);
        source.setCharEnd(111);
        when(documentContentMapper.searchChunks(1L, "\"上线检查\"", 6)).thenReturn(List.of(source));

        ChunkHitVO excerpt = service.searchChunks(1L, "上线检查", USER).get(0);

        assertEquals("1234567", excerpt.getExcerpt());
        assertEquals(107, excerpt.getCharEnd());
    }

    @Test
    void unreadDocumentIsNotPaged() {
        Document document = new Document();
        document.setSpaceId(1L);
        document.setParseStatus(ParseStatus.PENDING);
        when(documentMapper.selectById(10L)).thenReturn(document);

        ChunkReadVO page = service.readChunks(1L, 10L, 0, 5, USER);

        assertFalse(page.isAvailable());
        assertFalse(page.isHasMore());
        assertEquals(ParseStatus.PENDING, page.getParseStatus());
        verify(documentContentMapper, never()).readChunks(any(), any(), anyInt(), anyInt());
    }

    @Test
    void missingOrForeignDocumentIsReportedInsteadOfLookingUnparsed() {
        assertThrows(BusinessException.class, () -> service.readChunks(1L, 10L, 0, 5, USER));

        Document foreign = readyDocument();
        foreign.setSpaceId(2L);
        when(documentMapper.selectById(11L)).thenReturn(foreign);
        assertThrows(BusinessException.class, () -> service.readChunks(1L, 11L, 0, 5, USER));

        verify(documentContentMapper, never()).readChunks(any(), any(), anyInt(), anyInt());
    }

    @Test
    void readReportsMoreRowsAndClampsPageSize() {
        Document document = readyDocument();
        when(documentMapper.selectById(10L)).thenReturn(document);
        when(spaceMapper.selectById(1L)).thenReturn(new Space());
        when(documentContentMapper.readChunks(1L, 10L, 0, 21))
                .thenReturn(List.of(hit("123456"), hit("abcdef")));

        ChunkReadVO page = service.readChunks(1L, 10L, -3, 100, USER);

        assertTrue(page.isAvailable());
        assertTrue(page.isHasMore());
        assertEquals(1, page.getChunks().size());
        assertEquals("123456", page.getChunks().get(0).getExcerpt());
        assertEquals(3, page.getParseVersion());
    }

    @Test
    void oversizedReadChunkIsExplicitlyRejectedInsteadOfPartiallyConsumed() {
        when(documentMapper.selectById(10L)).thenReturn(readyDocument());
        when(spaceMapper.selectById(1L)).thenReturn(new Space());
        when(documentContentMapper.readChunks(1L, 10L, 0, 2)).thenReturn(List.of(hit("123456789")));

        assertThrows(BusinessException.class, () -> service.readChunks(1L, 10L, 0, 1, USER));
    }

    @Test
    void staleCitationDoesNotReturnText() {
        when(documentContentMapper.findReadableChunk(1L, 10L, 99L, 1)).thenReturn(null);

        ChunkCitationVO citation = service.resolveCitation(1L, 10L, 99L, 1, USER);

        assertFalse(citation.isAccessible());
        assertEquals("原引用已更新或不可访问", citation.getMessage());
        assertEquals(null, citation.getChunk());
    }

    @Test
    void citationKeepsVersionAndReportsShortenedRange() {
        ChunkHitVO current = hit("123456789");
        current.setCharStart(0);
        current.setCharEnd(9);
        when(documentContentMapper.findReadableChunk(1L, 10L, 99L, 3)).thenReturn(current);

        ChunkCitationVO citation = service.resolveCitation(1L, 10L, 99L, 3, USER);

        assertTrue(citation.isAccessible());
        assertEquals(3, citation.getChunk().getParseVersion());
        assertEquals("12345678", citation.getChunk().getExcerpt());
        assertEquals(8, citation.getChunk().getCharEnd());
    }

    @Test
    void elasticCandidatesAreVerifiedAndPartialIndexIsSupplementedWithoutDuplicates() {
        when(chunkIndex.enabled()).thenReturn(true);
        when(chunkIndex.search(1L, "上线检查", 6)).thenReturn(List.of(
                new ChunkIndexHit(99L, 10L, 3, "<mark>上线</mark>检查"),
                new ChunkIndexHit(88L, 11L, 1, "foreign")));
        when(documentContentMapper.findReadableChunk(1L, 10L, 99L, 3)).thenReturn(hit("上线检查"));
        ChunkHitVO extra = hit("补充");
        extra.setChunkId(100L);
        when(documentContentMapper.searchChunks(1L, "\"上线检查\"", 6)).thenReturn(List.of(hit("上线检查"), extra));

        List<ChunkHitVO> hits = service.searchChunks(1L, "上线检查", USER);

        assertEquals(List.of(99L, 100L), hits.stream().map(ChunkHitVO::getChunkId).toList());
        assertEquals("上线检查", hits.get(0).getExcerpt());
        assertEquals("<mark>上线</mark>检查", hits.get(0).getHighlight());
        verify(documentContentMapper).findReadableChunk(1L, 11L, 88L, 1);
    }

    @Test
    void elasticFailureFallsBackToMysql() {
        when(chunkIndex.enabled()).thenReturn(true);
        when(chunkIndex.search(1L, "上线检查", 6)).thenThrow(new IllegalStateException("offline"));
        when(documentContentMapper.searchChunks(1L, "\"上线检查\"", 6)).thenReturn(List.of(hit("备份")));
        assertEquals("备份", service.searchChunks(1L, "上线检查", USER).get(0).getExcerpt());
    }

    @Test
    void untrustedOrOutOfRangeHighlightNeverReplacesAuthoritativeText() {
        for (String highlight : List.of("<img src=x onerror=alert(1)>", "<mark>不在正文</mark>")) {
            when(chunkIndex.enabled()).thenReturn(true);
            when(chunkIndex.search(1L, "上线检查", 6)).thenReturn(List.of(new ChunkIndexHit(99L, 10L, 3, highlight)));
            when(documentContentMapper.findReadableChunk(1L, 10L, 99L, 3)).thenReturn(hit("上线检查"));
            List<ChunkHitVO> hits = service.searchChunks(1L, "上线检查", USER);
            assertEquals("上线检查", hits.get(0).getExcerpt());
            assertEquals(null, hits.get(0).getHighlight());
        }
    }

    private Document readyDocument() {
        Document document = new Document();
        document.setId(10L);
        document.setSpaceId(1L);
        document.setName("上线手册");
        document.setParseStatus(ParseStatus.READY);
        document.setParseVersion(3);
        return document;
    }

    private ChunkHitVO hit(String excerpt) {
        ChunkHitVO hit = new ChunkHitVO();
        hit.setChunkId(99L);
        hit.setDocumentId(10L);
        hit.setChunkIndex(0);
        hit.setParseVersion(3);
        hit.setDocumentName("上线手册");
        hit.setExcerpt(excerpt);
        return hit;
    }
}
