package asia.creat.service.impl;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.RetrievalProperties;
import asia.creat.entity.Document;
import asia.creat.entity.ParseStatus;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.SpaceMapper;
import asia.creat.security.LoginUser;
import asia.creat.service.DocumentChunkQueryService;
import asia.creat.vo.ChunkCitationVO;
import asia.creat.vo.ChunkHitVO;
import asia.creat.vo.ChunkReadVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentChunkQueryServiceImpl implements DocumentChunkQueryService {
    private static final String CITATION_MISS = "原引用已更新或不可访问";

    private final DocumentContentMapper documentContentMapper;
    private final DocumentMapper documentMapper;
    private final SpaceMapper spaceMapper;
    private final RetrievalProperties retrievalProperties;

    @Override
    @RequireSpaceRole
    public List<ChunkHitVO> searchChunks(@SpaceId Long spaceId, String keyword, LoginUser loginUser) {
        String query = matchQuery(keyword);
        int limit = Math.max(1, retrievalProperties.getSearchLimit());
        return fitExcerpts(documentContentMapper.searchChunks(spaceId, query, limit));
    }

    @Override
    @RequireSpaceRole
    public ChunkReadVO readChunks(@SpaceId Long spaceId, Long documentId, int startIndex, int limit, LoginUser loginUser) {
        Document document = documentMapper.selectById(documentId);
        if (document == null || !spaceId.equals(document.getSpaceId())) {
            throw new BusinessException("文件不存在");
        }
        ChunkReadVO page = new ChunkReadVO();
        page.setParseStatus(document.getParseStatus());
        page.setParseVersion(document.getParseVersion());
        page.setDocumentName(document.getName());
        page.setChunks(List.of());
        if (document.getParseStatus() != ParseStatus.READY) {
            return page;
        }
        if (spaceMapper.selectById(spaceId) == null) {
            throw new BusinessException("文件不存在");
        }

        int pageSize = Math.min(Math.max(1, limit), Math.max(1, retrievalProperties.getReadLimit()));
        List<ChunkHitVO> rows = documentContentMapper.readChunks(spaceId, documentId, Math.max(0, startIndex), pageSize + 1);
        boolean hasMore = rows.size() > pageSize;

        // 只返回完整分块，放不下的留到下一页；第一块就放不下时报错，不能截一段冒充读完
        int budget = maxChars();
        List<ChunkHitVO> chunks = new ArrayList<>();
        for (ChunkHitVO row : rows.subList(0, Math.min(rows.size(), pageSize))) {
            int size = row.getExcerpt() == null ? 0 : row.getExcerpt().length();
            if (size > budget) {
                if (chunks.isEmpty()) {
                    throw new BusinessException("单个分块超过读取字数上限");
                }
                hasMore = true;
                break;
            }
            budget -= size;
            chunks.add(row);
        }
        page.setAvailable(true);
        page.setHasMore(hasMore);
        page.setChunks(chunks);
        return page;
    }

    @Override
    @RequireSpaceRole
    public ChunkCitationVO resolveCitation(@SpaceId Long spaceId, Long documentId, Long chunkId,
                                           Integer parseVersion, LoginUser loginUser) {
        ChunkHitVO hit = documentContentMapper.findReadableChunk(spaceId, documentId, chunkId, parseVersion);
        ChunkCitationVO citation = new ChunkCitationVO();
        if (hit == null) {
            citation.setAccessible(false);
            citation.setMessage(CITATION_MISS);
            return citation;
        }
        citation.setAccessible(true);
        citation.setChunk(shorten(hit, maxChars()));
        return citation;
    }

    /**
     * ngram 词元长度是 2，单个字符在 MySQL 里查不到，直接报错，免得调用方以为库里没有。
     * 运算符当分隔符，每个词单独加引号，多个词不要求挨着，按相关度排序。
     */
    public static String matchQuery(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new BusinessException("搜索关键字不能为空");
        }
        List<String> terms = new ArrayList<>();
        for (String term : keyword.replaceAll("[+\\-><()~*\"@]", " ").trim().split("\\s+")) {
            if (term.codePointCount(0, term.length()) >= 2) {
                terms.add("\"" + term + "\"");
            }
        }
        if (terms.isEmpty()) {
            throw new BusinessException("搜索关键字至少需要两个连续字符");
        }
        return String.join(" ", terms);
    }

    // 搜索摘录合计不超过 maxChars，最后一条可以截短
    private List<ChunkHitVO> fitExcerpts(List<ChunkHitVO> rows) {
        int budget = maxChars();
        List<ChunkHitVO> hits = new ArrayList<>();
        for (ChunkHitVO row : rows) {
            if (budget <= 0) {
                break;
            }
            ChunkHitVO hit = shorten(row, budget);
            if (hit.getExcerpt().isEmpty()) {
                break;
            }
            budget -= hit.getExcerpt().length();
            hits.add(hit);
        }
        return hits;
    }

    // 截断时不拆开代理对，charEnd 改成实际截到的位置
    private ChunkHitVO shorten(ChunkHitVO row, int budget) {
        String text = row.getExcerpt() == null ? "" : row.getExcerpt();
        if (text.length() > budget) {
            int end = Character.isHighSurrogate(text.charAt(budget - 1)) ? budget - 1 : budget;
            text = text.substring(0, end);
            if (row.getCharStart() != null) {
                row.setCharEnd(row.getCharStart() + end);
            }
        }
        row.setExcerpt(text);
        return row;
    }

    private int maxChars() {
        return Math.max(1, retrievalProperties.getMaxChars());
    }
}
