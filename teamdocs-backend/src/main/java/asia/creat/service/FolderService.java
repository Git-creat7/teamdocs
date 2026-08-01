package asia.creat.service;

import asia.creat.dto.CreateFolderDTO;
import asia.creat.dto.MoveFolderDTO;
import asia.creat.dto.RenameFolderDTO;
import asia.creat.entity.Folder;
import asia.creat.security.LoginUser;

import java.util.List;

public interface FolderService {
    void createFolder(Long spaceId, CreateFolderDTO createFolderDTO, LoginUser loginUser);

    List<Folder> getSubFolder(Long spaceId, Long parentId, LoginUser loginUser);

    void renameFolder(Long spaceId, Long folderId, RenameFolderDTO dto, LoginUser loginUser);

    void deleteFolder(Long spaceId, Long folderId, LoginUser loginUser);

    void moveFolder(Long spaceId, Long folderId, MoveFolderDTO dto, LoginUser loginUser);
}
