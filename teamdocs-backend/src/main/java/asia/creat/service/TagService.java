package asia.creat.service;

import asia.creat.dto.CreateTagDTO;
import asia.creat.entity.Tag;
import asia.creat.security.LoginUser;

import java.util.List;

public interface TagService {
    void createTag(Long spaceId, CreateTagDTO dto, LoginUser loginUser);

    List<Tag> getTags(Long spaceId, LoginUser loginUser);

    void deleteTag(Long spaceId, Long tagId, LoginUser loginUser);

    void addTagToDocument(Long spaceId, Long documentId, Long tagId, LoginUser loginUser);

    void renameTag(Long spaceId, Long tagId, String newName, LoginUser loginUser);

    void removeTagFromDocument(Long spaceId, Long documentId, Long tagId, LoginUser loginUser);

}
