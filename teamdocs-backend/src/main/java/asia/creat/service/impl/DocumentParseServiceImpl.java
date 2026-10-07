package asia.creat.service.impl;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.BucketType;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.ParseProperties;
import asia.creat.entity.Document;
import asia.creat.entity.DocumentContent;
import asia.creat.entity.ParseStatus;
import asia.creat.entity.SpaceMember;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.parse.DocumentTextExtractor;
import asia.creat.parse.DocumentImageReader;
import asia.creat.parse.ExtractedText;
import asia.creat.parse.TextChunker;
import asia.creat.retrieval.RetrievalContext;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.DocumentContentService;
import asia.creat.service.DocumentParseService;
import asia.creat.service.DocumentIndexSync;
import asia.creat.service.FileStorageService;
import asia.creat.vo.DocumentParseStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static asia.creat.parse.DocumentParseWorker.SOURCE_CACHE;

import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaQueryChain;
import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaUpdateChain;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentParseServiceImpl implements DocumentParseService {
    private final DocumentMapper documentMapper;
    private final DocumentContentService documentContentService;
    private final FileStorageService fileStorageService;
    private final DocumentTextExtractor textExtractor;
    private final ParseProperties parseProperties;
    private final ResourcePermissionHelper permissionHelper;
    private final DocumentIndexSync documentIndexSync;

    @Override
    public void parseDocument(Long documentId) {
        Document document = documentMapper.selectById(documentId);

        if (document == null || document.getParseStatus() != ParseStatus.PENDING || document.getParseVersion() == null) {
            return;
        }

        boolean claimed = lambdaUpdateChain(documentMapper)
                .eq(Document::getId, documentId)
                .eq(Document::getParseVersion, document.getParseVersion())
                .eq(Document::getParseStatus, ParseStatus.PENDING)
                .exists("SELECT 1 FROM space s WHERE s.id = document.space_id AND s.deleted = 0")
                .set(Document::getParseStatus, ParseStatus.PARSING)
                .set(Document::getParseStartedAt, LocalDateTime.now())
                .set(Document::getParseError, null)
                .set(Document::getParsedAt, null)
                .set(Document::getChunkCount, 0)
                .setSql("updated_at = updated_at").update();

        if (!claimed) {
            return;
        }

        try {
            parseClaimed(document);
        } catch (RuntimeException e) {
            log.warn("文档 {} 解析失败: {}", documentId, e.getClass().getSimpleName());
            markFailed(document, "文档解析失败，请检查文件后重试");
        }
    }

    @Override
    @RequireSpaceRole
    public DocumentParseStatusVO getStatus(@SpaceId Long spaceId, Long documentId, LoginUser loginUser) {
        return toStatus(requireDocument(spaceId, documentId));
    }

    @Override
    @RequireSpaceRole
    public DocumentParseStatusVO reparse(@SpaceId Long spaceId, Long documentId, LoginUser loginUser) {
        Document document = requireDocument(spaceId, documentId);
        SpaceMember member = SpaceContext.getSpaceMember();

        permissionHelper.checkOwnerOrCreator(member, document.getUploadBy(), loginUser.getUserId());

        LocalDateTime retryBefore = LocalDateTime.now().minusSeconds(Math.max(1, parseProperties.getRetryDelaySeconds()));

        if (document.getParseStartedAt() != null && document.getParseStartedAt().isAfter(retryBefore)) {
            throw new BusinessException("重新解析过于频繁，请稍后再试");
        }

        boolean updated = lambdaUpdateChain(documentMapper)
                .eq(Document::getId, documentId)
                .eq(Document::getSpaceId, spaceId)
                .eq(Document::getParseVersion, document.getParseVersion())
                .in(Document::getParseStatus, ParseStatus.FAILED, ParseStatus.SKIPPED, ParseStatus.READY)
                .set(Document::getParseStatus, ParseStatus.PENDING)
                .set(Document::getParseError, null)
                .set(Document::getParseStartedAt, null)
                .set(Document::getParsedAt, null)
                .set(Document::getChunkCount, 0)
                .setSql("parse_version = parse_version + 1")
                .setSql("updated_at = updated_at").update();

        if (!updated) {
            throw new BusinessException("当前状态不能重新解析");
        }

        document.setParseStatus(ParseStatus.PENDING);
        document.setParseError(null);
        document.setParseStartedAt(null);
        document.setParsedAt(null);
        document.setChunkCount(0);
        document.setParseVersion(document.getParseVersion() + 1);

        documentIndexSync.afterCommit(documentId);

        return toStatus(document);
    }

    private void parseClaimed(Document document) {
        if (document.getFileSize() != null && document.getFileSize() > parseProperties.getMaxBytes()) {
            markSkipped(document, "文件超过解析大小上限");

            return;
        }

        if (!DocumentTextExtractor.supported(document.getName(), document.getFileType())) {
            markSkipped(document, "不支持解析该文件类型");

            return;
        }

        long deadline = System.currentTimeMillis() + Math.max(1, parseProperties.getTimeoutSeconds()) * 1000L;
        ExtractedText extracted;

        try (InputStream input = fileStorageService.open(BucketType.PRIVATE, document.getFilePath());
            RetrievalContext ignored = RetrievalContext.open(deadline, () -> requireCurrentParse(document), hit -> { })) {
            byte[] source = DocumentImageReader.readLimited(input, parseProperties.getMaxBytes());

            SOURCE_CACHE.put(document.getId() + ":" + document.getParseVersion(), source);
            // 缓存和提取器复用同一份原件，避免再包装成流后完整复制一次
            extracted = textExtractor.extractSource(document.getName(), document.getFileType(), source);
        } catch (Exception e) {
            log.warn("文档 {} 读取或提取失败: {}", document.getId(), e.getClass().getSimpleName());
            markFailed(document, "文件读取或解析失败，请检查文件后重试");

            return;
        }

        if (extracted.isSkipped()) {
            markSkipped(document, extracted.getReason());

            return;
        }

        List<DocumentContent> chunks = chunk(extracted);

        if (chunks.isEmpty()) {
            markSkipped(document, "没有可提取文本");

            return;
        }

        boolean published = documentContentService.publishIfParsing(
                document.getId(),
                document.getSpaceId(),
                document.getParseVersion(),
                chunks
        );

        if (!published) {
            log.info("文档 {} 的解析结果已过期，放弃发布", document.getId());

            return;
        }

        documentIndexSync.afterCommit(document.getId());
    }

    /** 每次视觉请求前后检查任务版本，失效后不继续发送下一幅图。 */
    private void requireCurrentParse(Document document) {
        if (Thread.currentThread().isInterrupted() || lambdaQueryChain(documentMapper)
                .eq(Document::getId, document.getId())
                .eq(Document::getParseVersion, document.getParseVersion())
                .eq(Document::getParseStatus, ParseStatus.PARSING)
                .exists("SELECT 1 FROM space s WHERE s.id = document.space_id AND s.deleted = 0").count() != 1) {
            throw new IllegalStateException("解析任务已失效");
        }
    }

    private List<DocumentContent> chunk(ExtractedText extracted) {
        List<DocumentContent> chunks = new ArrayList<>();
        int index = 0;

        for (ExtractedText.Segment segment : extracted.getSegments()) {
            List<DocumentContent> parts = TextChunker.chunk(
                    segment.text(),
                    segment.pageNumber(),
                    parseProperties.getChunkSize(),
                    parseProperties.getChunkOverlap()
            );

            for (DocumentContent part : parts) {
                part.setChunkIndex(index++);
                part.setImageRef(segment.imageRef());
                part.setImageLabel(segment.imageLabel());

                chunks.add(part);
            }
        }

        return chunks;
    }

    private void markSkipped(Document document, String reason) {
        boolean updated = documentContentService.discardIfParsing(
                document.getId(),
                document.getSpaceId(),
                document.getParseVersion(),
                ParseStatus.SKIPPED,
                reason
        );

        if (!updated) {
            log.info("文档 {} 已不是当前解析任务，跳过结果未写入", document.getId());

            return;
        }
    }

    private void markFailed(Document document, String reason) {
        try {
            documentContentService.discardIfParsing(
                    document.getId(),
                    document.getSpaceId(),
                    document.getParseVersion(),
                    ParseStatus.FAILED,
                    reason
            );
        } catch (RuntimeException ignored) {
            log.warn("文档 {} 失败状态未能写入", document.getId());
        }
    }

    private Document requireDocument(Long spaceId, Long documentId) {
        Document document = documentMapper.selectById(documentId);

        if (document == null || !spaceId.equals(document.getSpaceId())) {
            throw new BusinessException("文件不存在");
        }

        return document;
    }

    private DocumentParseStatusVO toStatus(Document document) {
        return DocumentParseStatusVO.builder()
                .documentId(document.getId())
                .parseStatus(document.getParseStatus())
                .chunkCount(document.getChunkCount())
                .parseError(document.getParseError())
                .parsedAt(document.getParsedAt())
                .parseVersion(document.getParseVersion())
                .build();
    }
}
