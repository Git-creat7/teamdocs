package asia.creat.service;

import asia.creat.dto.MoveDocumentDTO;
import asia.creat.dto.RenameDocumentDTO;
import asia.creat.entity.Document;
import asia.creat.security.LoginUser;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {
    void upload(Long spaceId, Long folderId, MultipartFile file, LoginUser loginUser);

    List<Document> listByFolder(Long spaceId, Long folderId, LoginUser loginUser);

    void deleteDocument(Long spaceId, Long documentId, LoginUser loginUser);

    void renameDocument(Long spaceId, Long documentId, RenameDocumentDTO dto, LoginUser loginUser);

    void moveDocument(Long spaceId, Long documentId, MoveDocumentDTO dto, LoginUser loginUser);

    String downloadDocument(Long spaceId, Long documentId, LoginUser loginUser);

    List<Document> listTrashedDocuments(Long spaceId, LoginUser loginUser);

    void restoreDocument(Long spaceId, Long documentId, Long targetFolderId, LoginUser loginUser);

    void purgeDocument(Long spaceId, Long documentId, LoginUser loginUser);
}
