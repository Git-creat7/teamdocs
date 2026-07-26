package asia.creat.service.impl;

import asia.creat.anno.OperationLog;
import asia.creat.anno.OperationTarget;
import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.BucketType;
import asia.creat.common.PageResult;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.MoveDocumentDTO;
import asia.creat.dto.PageQuery;
import asia.creat.dto.RenameDocumentDTO;
import asia.creat.entity.Document;
import asia.creat.entity.DocumentTag;
import asia.creat.entity.Folder;
import asia.creat.entity.SpaceMember;
import asia.creat.entity.Tag;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.DocumentTagMapper;
import asia.creat.mapper.FolderMapper;
import asia.creat.mapper.TagMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.DocumentService;
import asia.creat.service.FileStorageService;
import asia.creat.service.RecentDocumentService;
import asia.creat.vo.DocumentDetailVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class DocumentServiceImpl implements DocumentService {
    private final FileStorageService fileStorageService;
    private final DocumentMapper documentMapper;
    private final ResourcePermissionHelper permissionHelper;
    private final FolderMapper folderMapper;
    private final RecentDocumentService recentDocumentService;
    private final DocumentTagMapper documentTagMapper;
    private final TagMapper tagMapper;

    public DocumentServiceImpl(DocumentMapper documentMapper, FileStorageService fileStorageService, ResourcePermissionHelper permissionHelper, FolderMapper folderMapper, RecentDocumentService recentDocumentService, DocumentTagMapper documentTagMapper, TagMapper tagMapper) {
        this.documentMapper = documentMapper;
        this.fileStorageService = fileStorageService;
        this.permissionHelper = permissionHelper;
        this.folderMapper = folderMapper;
        this.recentDocumentService = recentDocumentService;
        this.documentTagMapper = documentTagMapper;
        this.tagMapper = tagMapper;
    }

    @Override
    @OperationLog(value = "上传文档", resourceType = "DOCUMENT")
    @RequireSpaceRole
    public void upload(@SpaceId Long spaceId, Long folderId, MultipartFile file, LoginUser loginUser) {

        String originalName = file.getOriginalFilename();
        if(originalName == null) {
            throw new BusinessException("文件名不能为空");
        }
        folderId = requireFolderInSpace(spaceId, folderId);
        String ext = originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf("."))
                : "";
        String objectKey = String.format(
                "space/%d/%s/%s%s",
                spaceId,
                YearMonth.now(),
                UUID.randomUUID(),
                ext
        );

        fileStorageService.upload(file, BucketType.PRIVATE, objectKey);
        Document doc = new Document();
        doc.setSpaceId(spaceId);
        doc.setFolderId(folderId);
        doc.setName(originalName);
        doc.setFilePath(objectKey);
        doc.setFileSize(file.getSize());
        doc.setFileType(file.getContentType());
        doc.setUploadBy(loginUser.getUserId());
        try {
            int inserted = documentMapper.insert(doc);
            if (inserted != 1) {
                throw new BusinessException("文件信息保存失败");
            }
        } catch (RuntimeException e) {
            try {
                fileStorageService.delete(BucketType.PRIVATE, objectKey);
            } catch (RuntimeException cleanupException) {
                log.error("文件信息保存失败，清理 MinIO 对象失败: objectKey={}", objectKey, cleanupException);
                e.addSuppressed(cleanupException);
            }
            throw e;
        }

        log.debug("用户 {} 上传了文件 {} 到空间 {} 的文件夹 {}",loginUser.getUserId(), originalName, spaceId, folderId);
    }

    @Override
    @RequireSpaceRole
    public PageResult<Document> listByFolder(@SpaceId Long spaceId, Long folderId, PageQuery pageQuery, LoginUser loginUser) {
        folderId = requireFolderInSpace(spaceId, folderId);

        LambdaQueryWrapper<Document> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Document::getSpaceId, spaceId)
                .eq(Document::getFolderId, folderId)
                .orderByDesc(Document::getUpdatedAt)
                .orderByDesc(Document::getId);
        return PageResult.from(documentMapper.selectPage(pageQuery.toPage(), lqw));
    }

    @Override
    @OperationLog(value = "重命名文档", resourceType = "DOCUMENT")
    @RequireSpaceRole
    public void renameDocument(@SpaceId Long spaceId, @OperationTarget Long documentId, RenameDocumentDTO dto, LoginUser loginUser) {

        Document doc = checkDocument(documentId, spaceId);

        SpaceMember member = SpaceContext.getSpaceMember();
        permissionHelper.checkOwnerOrCreator(member, doc.getUploadBy(), loginUser.getUserId());

        doc.setName(dto.getNewName());
        documentMapper.updateById(doc);

        log.debug("用户 {} 将空间 {} 的文件 {} 重命名为 {}", loginUser.getUserId(), spaceId, doc.getName(), dto.getNewName());
    }

    @Override
    @OperationLog(value = "删除文档", resourceType = "DOCUMENT")
    @RequireSpaceRole
    public void deleteDocument(@SpaceId Long spaceId, @OperationTarget Long documentId, LoginUser loginUser) {

        Document doc = checkDocument(documentId, spaceId);

        SpaceMember member = SpaceContext.getSpaceMember();
        permissionHelper.checkOwnerOrCreator(member,doc.getUploadBy(),loginUser.getUserId());

        documentMapper.deleteById(documentId);

        log.debug("用户 {} 删除了空间 {} 的文件 {}", loginUser.getUserId(), spaceId, doc.getName());
    }

    @Override
    @OperationLog(value = "移动文档", resourceType = "DOCUMENT")
    @RequireSpaceRole
    public void moveDocument(@SpaceId Long spaceId,@OperationTarget Long documentId, MoveDocumentDTO dto, LoginUser loginUser) {

        Document doc = checkDocument(documentId, spaceId);

        Long targetFolderId = requireFolderInSpace(spaceId, dto.getTargetFolderId());

        SpaceMember member = SpaceContext.getSpaceMember();
        permissionHelper.checkOwnerOrCreator(member, doc.getUploadBy(), loginUser.getUserId());

        doc.setFolderId(targetFolderId);
        documentMapper.updateById(doc);

        log.debug("用户 {} 将空间 {} 的文件 {} 移动到文件夹 {}", loginUser.getUserId(), spaceId, doc.getName(), targetFolderId);
    }

    @Override
    @RequireSpaceRole
    public DocumentDetailVO getDocumentDetail(@SpaceId Long spaceId, Long documentId, LoginUser loginUser) {

        Document doc = checkDocument(documentId, spaceId);

        List<Long> tagIds = documentTagMapper.selectList(new LambdaQueryWrapper<DocumentTag>()
                        .eq(DocumentTag::getDocumentId, documentId))
                .stream().map(DocumentTag::getTagId).toList();
        List<Tag> tags = tagIds.isEmpty() ? List.of() : tagMapper.selectBatchIds(tagIds);

        DocumentDetailVO vo = new DocumentDetailVO();
        BeanUtils.copyProperties(doc, vo);
        vo.setTags(tags);

        recentDocumentService.recordRecentDocument(loginUser.getUserId(), documentId);
        return vo;
    }

    @Override
    @RequireSpaceRole
    public String downloadDocument(@SpaceId Long spaceId, Long documentId, LoginUser loginUser) {

        Document doc = checkDocument(documentId, spaceId);

        String url = fileStorageService.getAccessUrl(
                    BucketType.PRIVATE,
                    doc.getFilePath(),
                    Map.of("response-content-disposition", "attachment; filename=\"" + doc.getName() + "\"")
                );
        recentDocumentService.recordRecentDocument(loginUser.getUserId(), documentId);
        return url;

    }


    @Override
    @RequireSpaceRole
    public PageResult<Document> listTrashedDocuments(@SpaceId Long spaceId, PageQuery pageQuery, LoginUser loginUser) {

        return PageResult.from(documentMapper.selectTrashedDocuments(pageQuery.toPage(), spaceId));

    }

    @Override
    @OperationLog(value = "恢复文档", resourceType = "DOCUMENT")
    @RequireSpaceRole
    public void restoreDocument(@SpaceId Long spaceId,@OperationTarget Long documentId, Long targetFolderId, LoginUser loginUser) {

        Document doc = documentMapper.selectDeletedDocument(documentId);

        if (doc == null) {
            throw new BusinessException("文件不存在");
        }

        if (!doc.getSpaceId().equals(spaceId)) {
            throw new BusinessException("文件不属于当前空间");
        }

        SpaceMember member = SpaceContext.getSpaceMember();
        permissionHelper.checkOwnerOrCreator(member, doc.getUploadBy(), loginUser.getUserId());

        if (targetFolderId == null) {
            targetFolderId = doc.getFolderId();
            if (targetFolderId != 0) {
                Folder originalFolder = folderMapper.selectById(targetFolderId);
                if (originalFolder == null || !originalFolder.getSpaceId().equals(spaceId)) {
                    targetFolderId = 0L;
                }
            }
        } else if (targetFolderId != 0) {
            Folder folder = folderMapper.selectById(targetFolderId);
            if (folder == null || !folder.getSpaceId().equals(spaceId)) {
                throw new BusinessException("目标文件夹不存在");
            }
        }


        documentMapper.updateDeleted(documentId, targetFolderId);
    }

    @Override
    @OperationLog(value = "彻底删除文档", resourceType = "DOCUMENT")
    @RequireSpaceRole
    public void purgeDocument(@SpaceId Long spaceId, @OperationTarget Long documentId, LoginUser loginUser) {

        Document doc = documentMapper.selectDeletedDocument(documentId);

        if (doc == null) {
            throw new BusinessException("文件不存在");
        }

        if (!doc.getSpaceId().equals(spaceId)) {
            throw new BusinessException("文件不属于当前空间");
        }

        SpaceMember member = SpaceContext.getSpaceMember();
        permissionHelper.checkOwnerOrCreator(member, doc.getUploadBy(), loginUser.getUserId());

        fileStorageService.delete(BucketType.PRIVATE, doc.getFilePath());
        boolean flag = documentMapper.purgeDeleteById(documentId);

        if (!flag) {
            log.error("彻底删除文件 {} 失败", documentId);
            throw new BusinessException("文件删除失败");
        }
    }

    @Override
    @RequireSpaceRole
    public PageResult<Document> searchDocuments(@SpaceId Long spaceId, String keyword, PageQuery pageQuery, LoginUser loginUser) {
        if(keyword == null || keyword.trim().isEmpty()) {
            throw new BusinessException("搜索关键字不能为空");
        }
        var page = pageQuery.<Document>toPage();
        // DISTINCT + JOIN 需要用原查询计算准确总数。
        page.setOptimizeCountSql(false);
        return PageResult.from(documentMapper.searchDocuments(page, spaceId, keyword.trim()));
    }


    /*
     * 检查文档
     * */
    private Document checkDocument(Long documentId, Long spaceId) {

        Document doc = documentMapper.selectById(documentId);

        if (doc == null) {
            throw new BusinessException("文件不存在");
        }

        if (!doc.getSpaceId().equals(spaceId)) {
            throw new BusinessException("文件不属于当前空间");
        }

        return doc;
    }

    private Long requireFolderInSpace(Long spaceId, Long folderId) {
        if (folderId == null || folderId == 0) {
            return 0L;
        }
        if (folderMapper.selectCount(new LambdaQueryWrapper<Folder>()
                .eq(Folder::getId, folderId)
                .eq(Folder::getSpaceId, spaceId)) == 0) {
            throw new BusinessException("目标文件夹不存在");
        }
        return folderId;
    }

}
