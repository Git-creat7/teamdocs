package asia.creat.service;

import asia.creat.common.PageResult;
import asia.creat.dto.MoveDocumentDTO;
import asia.creat.dto.PageQuery;
import asia.creat.dto.RenameDocumentDTO;
import asia.creat.entity.Document;
import asia.creat.security.LoginUser;
import asia.creat.vo.DocumentDetailVO;
import asia.creat.vo.DocumentPreviewVO;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {
    Long upload(Long spaceId, Long folderId, MultipartFile file, LoginUser loginUser);

    PageResult<Document> listByFolder(Long spaceId, Long folderId, PageQuery pageQuery, LoginUser loginUser);

    void deleteDocument(Long spaceId, Long documentId, LoginUser loginUser);

    void renameDocument(Long spaceId, Long documentId, RenameDocumentDTO dto, LoginUser loginUser);

    void moveDocument(Long spaceId, Long documentId, MoveDocumentDTO dto, LoginUser loginUser);

    String downloadDocument(Long spaceId, Long documentId, LoginUser loginUser);

    DocumentPreviewVO previewDocument(Long spaceId, Long documentId, LoginUser loginUser);

    DocumentDetailVO getDocumentDetail(Long spaceId, Long documentId, LoginUser loginUser);

    PageResult<Document> listTrashedDocuments(Long spaceId, PageQuery pageQuery, LoginUser loginUser);

    void restoreDocument(Long spaceId, Long documentId, Long targetFolderId, LoginUser loginUser);

    void purgeDocument(Long spaceId, Long documentId, LoginUser loginUser);

    PageResult<Document> searchDocuments(Long spaceId, String keyword, PageQuery pageQuery, LoginUser loginUser);
}
