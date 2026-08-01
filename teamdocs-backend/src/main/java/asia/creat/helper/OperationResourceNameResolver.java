package asia.creat.helper;

import asia.creat.entity.Folder;
import asia.creat.entity.Tag;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.FolderMapper;
import asia.creat.mapper.TagMapper;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class OperationResourceNameResolver {
    private final DocumentMapper documentMapper;
    private final FolderMapper folderMapper;
    private final TagMapper tagMapper;

    public String resolve(String resourceType, Long resourceId, Long spaceId) {
        if (StrUtil.isBlank(resourceType) || resourceId == null || spaceId == null) {
            return null;
        }

        try {

            switch (resourceType) {
                case "DOCUMENT" -> {
                    return documentMapper.selectNameIncludingDeleted(spaceId, resourceId);
                }

                case "FOLDER" -> {
                    Folder folder = folderMapper.selectById(resourceId);
                    if (folder == null || !spaceId.equals(folder.getSpaceId())) {
                        return null;
                    }
                    return folder.getName();
                }

                case "TAG" -> {
                    Tag tag = tagMapper.selectById(resourceId);
                    if (tag == null || !spaceId.equals(tag.getSpaceId())) {
                        return null;
                    }
                    return tag.getName();
                }
            }

        }catch (Exception e) {
            log.warn("资源名称解析失败 resourceType: {}, resourceId: {}, spaceId: {}",
                    resourceType, resourceId, spaceId, e);
        }

        return null;
    }
}
