package asia.creat.service.impl;

import asia.creat.entity.Document;
import asia.creat.entity.DocumentContent;
import asia.creat.entity.ParseStatus;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.DocumentMapper;
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
    public List<DocumentContent> getChunksByDocumentId(Long documentId) {
        if (documentId == null) {
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
}
