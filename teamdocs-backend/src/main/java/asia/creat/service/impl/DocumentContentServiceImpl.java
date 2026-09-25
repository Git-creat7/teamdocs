package asia.creat.service.impl;

import asia.creat.entity.Document;
import asia.creat.entity.DocumentContent;
import asia.creat.entity.ParseStatus;
import asia.creat.entity.Space;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.SpaceMapper;
import asia.creat.service.DocumentContentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentContentServiceImpl implements DocumentContentService {

    private final DocumentContentMapper documentContentMapper;
    private final DocumentMapper documentMapper;
    private final SpaceMapper spaceMapper;

    @Override
    public void recordParsing(Long documentId) {
        documentMapper.update(
                null,
                new LambdaUpdateWrapper<Document>()
                        .eq(Document::getId, documentId)
                        .set(Document::getParseStatus, ParseStatus.PARSING)
                        .set(Document::getParseError, null)
        );
        log.debug("文档 {} 状态更新为 PARSING", documentId);
    }

    @Override
    @Transactional
    public void saveChunks(Long documentId, Long spaceId, List<DocumentContent> chunks) {
        // 1. 删除原有的切片，保证重复解析时的幂等性
        documentContentMapper.delete(
                new LambdaQueryWrapper<DocumentContent>()
                        .eq(DocumentContent::getDocumentId, documentId)
        );

        // 2. 插入新切片
        int count = 0;
        if (chunks != null && !chunks.isEmpty()) {
            for (DocumentContent chunk : chunks) {
                chunk.setDocumentId(documentId);
                chunk.setSpaceId(spaceId);
                documentContentMapper.insert(chunk);
            }
            count = chunks.size();
        }

        // 3. 更新 document 表中的解析状态与切片数
        documentMapper.update(
                null,
                new LambdaUpdateWrapper<Document>()
                        .eq(Document::getId, documentId)
                        .set(Document::getParseStatus, ParseStatus.READY)
                        .set(Document::getChunkCount, count)
                        .set(Document::getParsedAt, LocalDateTime.now())
                        .set(Document::getParseError, null)
        );
        log.debug("文档 {} 保存了 {} 个切片，状态更新为 READY", documentId, count);
    }

    @Override
    public void recordParseFailure(Long documentId, String errorMessage) {
        String truncatedError = errorMessage != null && errorMessage.length() > 500
                ? errorMessage.substring(0, 500)
                : errorMessage;

        documentMapper.update(
                null,
                new LambdaUpdateWrapper<Document>()
                        .eq(Document::getId, documentId)
                        .set(Document::getParseStatus, ParseStatus.FAILED)
                        .set(Document::getParseError, truncatedError)
        );
        log.warn("文档 {} 解析失败，状态更新为 FAILED，错误信息: {}", documentId, truncatedError);
    }

    @Override
    public void resetParseStatus(Long documentId) {
        documentMapper.update(
                null,
                new LambdaUpdateWrapper<Document>()
                        .eq(Document::getId, documentId)
                        .set(Document::getParseStatus, ParseStatus.PENDING)
                        .set(Document::getChunkCount, 0)
                        .set(Document::getParseError, null)
                        .set(Document::getParsedAt, null)
        );
        log.debug("重置文档 {} 解析状态为 PENDING", documentId);
    }

    @Override
    @Transactional
    public boolean publishIfParsing(Long documentId, Long spaceId, Integer parseVersion, List<DocumentContent> chunks) {
        Document locked = documentMapper.lockById(documentId);
        if (!isCurrentParsing(locked, spaceId, parseVersion) || !Integer.valueOf(0).equals(locked.getDeleted())) {
            return false;
        }
        Space space = spaceMapper.selectById(spaceId);
        if (space == null) {
            return false;
        }
        replaceChunks(documentId, spaceId, chunks);
        int updated = finishParsing(
                documentId,
                parseVersion,
                ParseStatus.READY,
                chunks == null ? 0 : chunks.size(),
                null,
                LocalDateTime.now()
        );
        if (updated != 1) {
            throw new IllegalStateException("解析发布条件失效");
        }
        return true;
    }

    @Override
    @Transactional
    public boolean discardIfParsing(Long documentId, Long spaceId, Integer parseVersion, ParseStatus status, String reason) {
        if (status != ParseStatus.FAILED && status != ParseStatus.SKIPPED) {
            throw new IllegalArgumentException("只能结束为 FAILED 或 SKIPPED");
        }
        Document locked = documentMapper.lockById(documentId);
        if (!isCurrentParsing(locked, spaceId, parseVersion)) {
            return false;
        }
        if (Integer.valueOf(0).equals(locked.getDeleted())) {
            documentContentMapper.delete(
                    new LambdaQueryWrapper<DocumentContent>()
                            .eq(DocumentContent::getDocumentId, documentId)
            );
        }
        int updated = finishParsing(documentId, parseVersion, status, 0, truncate(reason), null);
        if (updated != 1) {
            throw new IllegalStateException("解析状态回写条件失效");
        }
        return true;
    }

    @Override
    public List<DocumentContent> getChunksByDocumentId(Long documentId) {
        if (documentId == null) {
            return Collections.emptyList();
        }
        Document document = documentMapper.selectById(documentId);
        if (document == null || document.getParseStatus() != ParseStatus.READY) {
            return Collections.emptyList();
        }
        if (spaceMapper.selectById(document.getSpaceId()) == null) {
            return Collections.emptyList();
        }
        return documentContentMapper.selectList(
                new LambdaQueryWrapper<DocumentContent>()
                        .eq(DocumentContent::getDocumentId, documentId)
                        .orderByAsc(DocumentContent::getChunkIndex)
        );
    }

    @Override
    @Transactional
    public void purgeByDocumentId(Long documentId) {
        documentContentMapper.delete(
                new LambdaQueryWrapper<DocumentContent>()
                        .eq(DocumentContent::getDocumentId, documentId)
        );
        log.debug("彻底清理文档 {} 的所有切片数据", documentId);
    }

    private int finishParsing(Long documentId, Integer parseVersion, ParseStatus status,
                              int chunkCount, String parseError, LocalDateTime parsedAt) {
        return documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, documentId)
                .eq(Document::getParseStatus, ParseStatus.PARSING)
                .eq(Document::getParseVersion, parseVersion)
                .set(Document::getParseStatus, status)
                .set(Document::getChunkCount, chunkCount)
                .set(Document::getParseError, parseError)
                .set(Document::getParsedAt, parsedAt)
                .setSql("updated_at = updated_at"));
    }

    private void replaceChunks(Long documentId, Long spaceId, List<DocumentContent> chunks) {
        documentContentMapper.delete(
                new LambdaQueryWrapper<DocumentContent>()
                        .eq(DocumentContent::getDocumentId, documentId)
        );
        if (chunks == null) {
            return;
        }
        for (DocumentContent chunk : chunks) {
            chunk.setDocumentId(documentId);
            chunk.setSpaceId(spaceId);
            documentContentMapper.insert(chunk);
        }
    }

    private boolean isCurrentParsing(Document locked, Long spaceId, Integer parseVersion) {
        return locked != null
                && spaceId.equals(locked.getSpaceId())
                && locked.getParseStatus() == ParseStatus.PARSING
                && parseVersion != null
                && parseVersion.equals(locked.getParseVersion());
    }

    private String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "解析失败";
        }
        String oneLine = reason.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500);
    }
}
