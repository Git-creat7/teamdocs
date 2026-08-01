package asia.creat.service.impl;

import asia.creat.anno.OperationLog;
import asia.creat.anno.OperationTarget;
import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.exception.BusinessException;
import asia.creat.common.PageResult;
import asia.creat.dto.CreateTagDTO;
import asia.creat.dto.PageQuery;
import asia.creat.entity.Document;
import asia.creat.entity.DocumentTag;
import asia.creat.entity.SpaceMember;
import asia.creat.entity.SpaceRole;
import asia.creat.entity.Tag;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.DocumentTagMapper;
import asia.creat.mapper.TagMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.TagService;
import asia.creat.vo.DocumentTagRelVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final TagMapper tagMapper;
    private final DocumentMapper documentMapper;
    private final ResourcePermissionHelper permissionHelper;
    private final DocumentTagMapper documentTagMapper;

    @Override
    @OperationLog(value = "创建标签", resourceType = "TAG", resourceName = "#dto.name")
    @RequireSpaceRole({SpaceRole.OWNER, SpaceRole.ADMIN})
    public void createTag(@SpaceId Long spaceId, CreateTagDTO dto, LoginUser loginUser) {

        Tag tag = new Tag();
        tag.setName(dto.getName());
        tag.setSpaceId(spaceId);

        tagMapper.insert(tag);
    }

    @Override
    @RequireSpaceRole
    public List<Tag> getTags(@SpaceId Long spaceId, LoginUser loginUser) {

        LambdaQueryWrapper<Tag> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Tag::getSpaceId, spaceId);

        return tagMapper.selectList(lqw);
    }

    @Override
    @Transactional
    @OperationLog(value = "删除标签", resourceType = "TAG")
    @RequireSpaceRole({SpaceRole.OWNER, SpaceRole.ADMIN})
    public void deleteTag(@SpaceId Long spaceId, @OperationTarget Long tagId, LoginUser loginUser) {

        checkTag(spaceId, tagId);

        LambdaQueryWrapper<DocumentTag> relationQuery = new LambdaQueryWrapper<>();
        relationQuery.eq(DocumentTag::getTagId, tagId);
        documentTagMapper.delete(relationQuery);

        LambdaQueryWrapper<Tag> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Tag::getSpaceId, spaceId);
        lqw.eq(Tag::getId, tagId);

        tagMapper.delete(lqw);
    }

    @Override
    @OperationLog(value = "为文件添加标签", resourceType = "DOCUMENT")
    @RequireSpaceRole
    public void addTagToDocument(@SpaceId Long spaceId, @OperationTarget Long documentId, Long tagId, LoginUser loginUser) {

        Document doc = checkDocument(spaceId, documentId);
        checkTag(spaceId, tagId);

        SpaceMember member = SpaceContext.getSpaceMember();
        permissionHelper.checkOwnerOrCreator(member, doc.getUploadBy(), loginUser.getUserId());

        DocumentTag dt = new DocumentTag();
        dt.setDocumentId(documentId);
        dt.setTagId(tagId);
        documentTagMapper.insert(dt);
    }

    @Override
    @OperationLog(value = "重命名标签", resourceType = "TAG", resourceName = "#newName")
    @RequireSpaceRole({SpaceRole.OWNER, SpaceRole.ADMIN})
    public void renameTag(@SpaceId Long spaceId, @OperationTarget Long tagId, String newName, LoginUser loginUser) {

        Tag tag = checkTag(spaceId, tagId);

        tag.setName(newName);
        tagMapper.updateById(tag);
    }

    @Override
    @OperationLog(value = "从文件移除标签", resourceType = "DOCUMENT")
    @RequireSpaceRole
    public void removeTagFromDocument(@SpaceId Long spaceId, @OperationTarget Long documentId, Long tagId, LoginUser loginUser) {

        Document doc = checkDocument(spaceId, documentId);
        checkTag(spaceId, tagId);

        SpaceMember member = SpaceContext.getSpaceMember();
        permissionHelper.checkOwnerOrCreator(member, doc.getUploadBy(), loginUser.getUserId());

        LambdaQueryWrapper<DocumentTag> lqw = new LambdaQueryWrapper<>();
        lqw.eq(DocumentTag::getDocumentId, documentId);
        lqw.eq(DocumentTag::getTagId, tagId);
        documentTagMapper.delete(lqw);
    }

    @Override
    @RequireSpaceRole
    public PageResult<Document> listDocumentsByTag(@SpaceId Long spaceId, Long tagId, PageQuery pageQuery, LoginUser loginUser) {
        checkTag(spaceId, tagId);

        return PageResult.from(documentMapper.listDocumentsByTag(pageQuery.toPage(), spaceId, tagId));
    }

    @Override
    @RequireSpaceRole
    public List<Tag> listTagsByDocument(@SpaceId Long spaceId, Long documentId, LoginUser loginUser) {
        checkDocument(spaceId, documentId);

        return tagMapper.selectTagsByDocumentId(documentId);
    }

    @Override
    @RequireSpaceRole
    public Map<Long, List<Tag>> listTagsByDocuments(@SpaceId Long spaceId, List<Long> documentIds, LoginUser loginUser) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        if (documentIds.size() > 200) {
            throw new BusinessException("单次最多查询 200 个文档");
        }

        // SQL 里 JOIN document 按 space_id 过滤，越权/不存在的 id 不会出现在结果里；
        // 请求过但无标签的文档补空列表，前端可据此区分"没标签"和"没查过"
        List<DocumentTagRelVO> rels = tagMapper.selectTagsByDocumentIds(spaceId, documentIds);

        Map<Long, List<Tag>> result = new LinkedHashMap<>();
        for (Long docId : documentIds) {
            result.put(docId, new ArrayList<>());
        }
        for (DocumentTagRelVO rel : rels) {
            Tag tag = new Tag();
            tag.setId(rel.getId());
            tag.setSpaceId(rel.getSpaceId());
            tag.setName(rel.getName());
            tag.setCreatedAt(rel.getCreatedAt());
            List<Tag> list = result.get(rel.getDocumentId());
            if (list != null) {
                list.add(tag);
            }
        }
        return result;
    }

    private Document checkDocument(Long spaceId, Long documentId) {
        Document doc = documentMapper.selectById(documentId);

        if (doc == null) {
            throw new BusinessException("文件不存在");
        }

        if (!doc.getSpaceId().equals(spaceId)) {
            throw new BusinessException("文件不属于当前空间");
        }

        return doc;
    }

    private Tag checkTag(Long spaceId, Long tagId) {
        Tag tag = tagMapper.selectById(tagId);

        if (tag == null) {
            throw new BusinessException("标签不存在");
        }

        if (!tag.getSpaceId().equals(spaceId)) {
            throw new BusinessException("标签不属于当前空间");
        }

        return tag;
    }

}
