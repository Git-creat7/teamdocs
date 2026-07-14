package asia.creat.service.impl;

import asia.creat.anno.OperationLog;
import asia.creat.anno.OperationTarget;
import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.CreateFolderDTO;
import asia.creat.dto.MoveFolderDTO;
import asia.creat.dto.RenameFolderDTO;
import asia.creat.entity.*;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.FolderMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.FolderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Slf4j
/*
* 仍然有Bug，后期优化修改
* */
public class FolderServiceImpl implements FolderService {
    private final FolderMapper folderMapper;
    private final DocumentMapper documentMapper;
    private final ResourcePermissionHelper permissionHelper;

    public FolderServiceImpl(FolderMapper folderMapper, DocumentMapper documentMapper,ResourcePermissionHelper permissionHelper) {
        this.folderMapper = folderMapper;
        this.documentMapper = documentMapper;
        this.permissionHelper = permissionHelper;
    }

    @Override
    @OperationLog(value = "创建文件夹", resourceType = "FOLDER")
    @RequireSpaceRole
    public void createFolder(@SpaceId Long spaceId, CreateFolderDTO dto, LoginUser loginUser) {

        //防止空指针异常，默认父目录id为0（根目录）
        Long parentId = dto.getParentId() == null ? 0L : dto.getParentId();
        if(parentId != 0){
            checkParentSpaceId(parentId, spaceId);
        }

        Folder folder = new Folder();
        folder.setName(dto.getName());
        folder.setParentId(parentId);
        folder.setSpaceId(spaceId);
        folder.setCreatedBy(loginUser.getUserId());
        folderMapper.insert(folder);
        log.info("{} 创建了文件夹：{}", loginUser.getUsername(), folder.getName());
    }

    @Override
    @RequireSpaceRole
    public List<Folder> getSubFolder(@SpaceId Long spaceId, Long parentId,LoginUser loginUser) {

        LambdaQueryWrapper<Folder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Folder::getSpaceId, spaceId)
                .eq(Folder::getParentId, parentId);
        return folderMapper.selectList(queryWrapper);
    }

    @Override
    @OperationLog(value = "重命名文件夹", resourceType = "FOLDER")
    @RequireSpaceRole
    public void renameFolder(@SpaceId Long spaceId, @OperationTarget Long folderId, RenameFolderDTO dto, LoginUser loginUser) {

        Folder folder = getFolder(folderId, spaceId);

        SpaceMember member = SpaceContext.getSpaceMember();
        permissionHelper.checkOwnerOrCreator(member, folder.getCreatedBy(), loginUser.getUserId());

        log.info("重命名文件夹：{} 将 {} 重命名为 {}", loginUser.getUsername(), folder.getName(),dto.getNewName());
        folder.setName(dto.getNewName());
        folderMapper.updateById(folder);
    }

    @Override
    @Transactional
    @OperationLog(value = "删除文件夹", resourceType = "FOLDER")
    @RequireSpaceRole
    public void deleteFolder(@SpaceId Long spaceId, @OperationTarget Long folderId, LoginUser loginUser) {

        Folder folder = getFolder(folderId, spaceId);

        SpaceMember member = SpaceContext.getSpaceMember();
        permissionHelper.checkOwnerOrCreator(member, folder.getCreatedBy(), loginUser.getUserId());

        List<Long> allIds = collectAllSubFolderIds(folderId);
        documentMapper.delete(new LambdaQueryWrapper<Document>().in(Document::getFolderId, allIds));
        folderMapper.deleteByIds(allIds);

        log.info("{} 删除了文件夹 {} 及其子文件夹", loginUser.getUsername(), folder.getName());
    }

    @Override
    @OperationLog(value = "移动文件夹", resourceType = "FOLDER")
    @RequireSpaceRole
    //移动时不要把目标文件夹 ID 当作 resourceId，日志记录的是“被移动的文件夹”
    public void moveFolder(@SpaceId Long spaceId,@OperationTarget Long folderId, MoveFolderDTO dto, LoginUser loginUser) {

        Folder folder = getFolder(folderId, spaceId);

        SpaceMember member = SpaceContext.getSpaceMember();
        permissionHelper.checkOwnerOrCreator(member, folder.getCreatedBy(), loginUser.getUserId());

        if (dto.getTargetParentId() != 0){
            checkParentSpaceId(dto.getTargetParentId(), spaceId);
        }

        if(Objects.equals(dto.getTargetParentId(), folderId)){
            throw new BusinessException("不能将文件夹移动到自己下面");
        }

        List<Long> allIds= collectAllSubFolderIds(folderId);

        if(allIds.contains(dto.getTargetParentId())){
            throw new BusinessException("不能将文件夹移动到自己的子目录下");
        }

        folder.setParentId(dto.getTargetParentId());
        folderMapper.updateById(folder);

        log.info("{} 将 {} 移动到了 「{}」", loginUser.getUsername(), folder.getName(), dto.getTargetParentId() == 0 ? "根目录" : "文件夹ID " + dto.getTargetParentId());
    }

    /*
    * 校验空间id，校验folder是否存在
    * */
    private void checkParentSpaceId(Long parentId, Long spaceId) {

        Folder parentFolder = folderMapper.selectById(parentId);
        if(parentFolder == null){
            throw new BusinessException("父目录不存在");
        }
        if (!parentFolder.getSpaceId().equals(spaceId)) {
            throw new BusinessException("父目录不属于当前空间");
        }
    }

    /*
    * 广度优先删除文件
    * */
    private List<Long> collectAllSubFolderIds(Long folderId) {

        List<Long> allIds = new ArrayList<>();
        Queue<Long> queue = new LinkedList<>();
        queue.offer(folderId);

        while (!queue.isEmpty()) {
            Long currentId = queue.poll();
            allIds.add(currentId);
            List<Folder> children = folderMapper.selectList(
                    new LambdaQueryWrapper<Folder>().eq(Folder::getParentId, currentId));
            for (Folder subFolder : children) {
                queue.offer(subFolder.getId());
            }
        }
        return allIds;
    }

    /*
    * 检查文件夹
    * */
    private Folder getFolder(Long folderId, Long spaceId) {

        Folder folder = folderMapper.selectById(folderId);

        if (folder == null) {
            throw new BusinessException("文件夹不存在");
        }

        if (!folder.getSpaceId().equals(spaceId)) {
            throw new BusinessException("文件夹不属于当前空间");
        }

        return folder;
    }
}
