package asia.creat.service.impl;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.BucketType;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.MoveDocumentDTO;
import asia.creat.dto.RenameDocumentDTO;
import asia.creat.entity.Document;
import asia.creat.entity.Folder;
import asia.creat.entity.SpaceMember;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.FolderMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.DocumentService;
import asia.creat.service.FileStorageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
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

    public DocumentServiceImpl(DocumentMapper documentMapper, FileStorageService fileStorageService, ResourcePermissionHelper permissionHelper, FolderMapper folderMapper) {
        this.documentMapper = documentMapper;
        this.fileStorageService = fileStorageService;
        this.permissionHelper = permissionHelper;
        this.folderMapper = folderMapper;
    }

    @Override
    @RequireSpaceRole
    public void upload(@SpaceId Long spaceId, Long folderId, MultipartFile file, LoginUser loginUser) {

        String originalName = file.getOriginalFilename();
        if(originalName == null) {
            throw new BusinessException("文件名不能为空");
        }
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
        doc.setName(file.getOriginalFilename());
        doc.setFilePath(objectKey);
        doc.setFileSize(file.getSize());
        doc.setFileType(file.getContentType());
        doc.setUploadBy(loginUser.getUserId());
        documentMapper.insert(doc);

        log.debug("用户 {} 上传了文件 {} 到空间 {} 的文件夹 {}",loginUser.getUserId(), originalName, spaceId, folderId);
    }

    @Override
    @RequireSpaceRole
    public List<Document> listByFolder(@SpaceId Long spaceId, Long folderId,LoginUser loginUser) {

        LambdaQueryWrapper<Document> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Document::getSpaceId, spaceId)
                .eq(Document::getFolderId, folderId);
        return documentMapper.selectList(lqw);
    }

    @Override
    @RequireSpaceRole
    public void deleteDocument(@SpaceId Long spaceId, Long documentId, LoginUser loginUser) {

        Document doc = getDocument(documentId, spaceId);

        SpaceMember member = SpaceContext.get();
        permissionHelper.checkOwnerOrCreator(member,doc.getUploadBy(),loginUser.getUserId());

        documentMapper.deleteById(documentId);

        log.debug("用户 {} 删除了空间 {} 的文件 {}", loginUser.getUserId(), spaceId, doc.getName());
    }

    @Override
    @RequireSpaceRole
    public void renameDocument(@SpaceId Long spaceId, Long documentId, RenameDocumentDTO dto, LoginUser loginUser) {

        Document doc = getDocument(documentId, spaceId);

        SpaceMember member = SpaceContext.get();
        permissionHelper.checkOwnerOrCreator(member, doc.getUploadBy(), loginUser.getUserId());

        doc.setName(dto.getNewName());
        documentMapper.updateById(doc);

        log.debug("用户 {} 将空间 {} 的文件 {} 重命名为 {}", loginUser.getUserId(), spaceId, doc.getName(), dto.getNewName());
    }

    @Override
    @RequireSpaceRole
    public void moveDocument(@SpaceId Long spaceId, Long documentId, MoveDocumentDTO dto, LoginUser loginUser) {

        Document doc = getDocument(documentId, spaceId);

        if(dto.getTargetFolderId() != null && dto.getTargetFolderId() != 0) {
            if(folderMapper.selectCount(new LambdaQueryWrapper<Folder>()
                    .eq(Folder::getId, dto.getTargetFolderId())
                    .eq(Folder::getSpaceId, spaceId)) == 0) {
                throw new BusinessException("目标文件夹不存在");
            }
        }

        SpaceMember member = SpaceContext.get();
        permissionHelper.checkOwnerOrCreator(member, doc.getUploadBy(), loginUser.getUserId());

        doc.setFolderId(dto.getTargetFolderId());
        documentMapper.updateById(doc);

        log.debug("用户 {} 将空间 {} 的文件 {} 移动到文件夹 {}", loginUser.getUserId(), spaceId, doc.getName(), dto.getTargetFolderId());
    }

    @Override
    @RequireSpaceRole
    public String downloadDocument(@SpaceId Long spaceId, Long documentId, LoginUser loginUser) {

        Document doc = getDocument(documentId, spaceId);

        return  fileStorageService.getAccessUrl(
                    BucketType.PRIVATE,
                    doc.getFilePath(),
                    Map.of("response-content-disposition", "attachment; filename=\"" + doc.getName() + "\"")
                );

    }


    @Override
    @RequireSpaceRole
    public List<Document> listTrashedDocuments(@SpaceId Long spaceId, LoginUser loginUser) {

        return documentMapper.selectTrashedDocuments(spaceId);

    }

    @Override
    @RequireSpaceRole
    public void restoreDocument(@SpaceId Long spaceId, Long documentId, Long targetFolderId, LoginUser loginUser) {

        Document doc = documentMapper.selectDeletedDocument(documentId);

        if (doc == null) {
            throw new BusinessException("文件不存在");
        }

        if (!doc.getSpaceId().equals(spaceId)) {
            throw new BusinessException("文件不属于当前空间");
        }

        SpaceMember member = SpaceContext.get();
        permissionHelper.checkOwnerOrCreator(member, doc.getUploadBy(), loginUser.getUserId());

        if(targetFolderId == null || targetFolderId == 0){
            if(doc.getFolderId() != 0) {
                Folder folder = folderMapper.selectById(doc.getFolderId());
                if(folder == null)
                    throw new BusinessException("原文件夹已经被删除，请选择目标文件夹");
            }
            targetFolderId = doc.getFolderId();

        }else{
            Folder folder = folderMapper.selectById(targetFolderId);
            if(folder == null || !folder.getSpaceId().equals(spaceId))
                throw new BusinessException("目标文件夹不存在");
        }


        documentMapper.updateDeleted(documentId, targetFolderId);
    }

    @Override
    @RequireSpaceRole
    public void purgeDocument(@SpaceId Long spaceId, Long documentId, LoginUser loginUser) {

        Document doc = documentMapper.selectDeletedDocument(documentId);

        if (doc == null) {
            throw new BusinessException("文件不存在");
        }

        if (!doc.getSpaceId().equals(spaceId)) {
            throw new BusinessException("文件不属于当前空间");
        }

        SpaceMember member = SpaceContext.get();
        permissionHelper.checkOwnerOrCreator(member, doc.getUploadBy(), loginUser.getUserId());

        boolean flag = documentMapper.purgeDeleteById(documentId);

        if (!flag) {
            log.error("彻底删除文件 {} 失败", documentId);
            throw new BusinessException("文件删除失败");
        }
    }

    /*
     * 检查文档
     * */
    private Document getDocument(Long documentId, Long spaceId) {

        Document doc = documentMapper.selectById(documentId);

        if (doc == null) {
            throw new BusinessException("文件不存在");
        }

        if (!doc.getSpaceId().equals(spaceId)) {
            throw new BusinessException("文件不属于当前空间");
        }

        return doc;
    }

}
