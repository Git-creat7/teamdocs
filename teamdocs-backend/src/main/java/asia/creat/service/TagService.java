package asia.creat.service;

import asia.creat.common.PageResult;
import asia.creat.dto.CreateTagDTO;
import asia.creat.dto.PageQuery;
import asia.creat.entity.Document;
import asia.creat.entity.Tag;
import asia.creat.security.LoginUser;

import java.util.List;
import java.util.Map;

public interface TagService {
    void createTag(Long spaceId, CreateTagDTO dto, LoginUser loginUser);

    List<Tag> getTags(Long spaceId, LoginUser loginUser);

    void deleteTag(Long spaceId, Long tagId, LoginUser loginUser);

    void addTagToDocument(Long spaceId, Long documentId, Long tagId, LoginUser loginUser);

    void renameTag(Long spaceId, Long tagId, String newName, LoginUser loginUser);

    void removeTagFromDocument(Long spaceId, Long documentId, Long tagId, LoginUser loginUser);

    PageResult<Document> listDocumentsByTag(Long spaceId, Long tagId, PageQuery pageQuery, LoginUser loginUser);

    List<Tag> listTagsByDocument(Long spaceId, Long documentId, LoginUser loginUser);

    Map<Long, List<Tag>> listTagsByDocuments(Long spaceId, List<Long> documentIds, LoginUser loginUser);
}
