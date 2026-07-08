package asia.creat.service.impl;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.CreateTagDTO;
import asia.creat.entity.*;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.DocumentTagMapper;
import asia.creat.mapper.TagMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.TagService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TagServiceImpl implements TagService {

    private final TagMapper tagMapper;
    private final DocumentMapper documentMapper;
    private final ResourcePermissionHelper permissionHelper;
    private final DocumentTagMapper documentTagMapper;

    public TagServiceImpl(TagMapper tagMapper, DocumentMapper documentMapper, ResourcePermissionHelper permissionHelper, DocumentTagMapper documentTagMapper) {
        this.tagMapper = tagMapper;
        this.documentMapper = documentMapper;
        this.permissionHelper = permissionHelper;
        this.documentTagMapper = documentTagMapper;
    }

    @Override
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
    @RequireSpaceRole({SpaceRole.OWNER, SpaceRole.ADMIN})
    public void deleteTag(@SpaceId Long spaceId, Long tagId, LoginUser loginUser) {

        if (tagMapper.selectById(tagId) == null) {
            throw new BusinessException("标签不存在");
        }

        LambdaQueryWrapper<Tag> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Tag::getSpaceId, spaceId);
        lqw.eq(Tag::getId, tagId);

        tagMapper.delete(lqw);
    }

    @Override
    @RequireSpaceRole
    public void addTagToDocument(@SpaceId Long spaceId, Long documentId, Long tagId, LoginUser loginUser) {

        Document doc = checkDocument(spaceId, documentId);

        SpaceMember member = SpaceContext.get();
        permissionHelper.checkOwnerOrCreator(member, doc.getUploadBy(), loginUser.getUserId());

        DocumentTag dt = new DocumentTag();
        dt.setDocumentId(documentId);
        dt.setTagId(tagId);
        documentTagMapper.insert(dt);
    }

    @Override
    @RequireSpaceRole({SpaceRole.OWNER, SpaceRole.ADMIN})
    public void renameTag(@SpaceId Long spaceId, Long tagId, String newName, LoginUser loginUser) {
        Tag tag  = tagMapper.selectById(tagId);
        if (tag == null) {
            throw new BusinessException("标签不存在");
        }

        tag.setName(newName);
        tagMapper.updateById(tag);
    }

    @Override
    @RequireSpaceRole
    public void removeTagFromDocument(@SpaceId Long spaceId, Long documentId, Long tagId, LoginUser loginUser) {

        Document doc = checkDocument(spaceId, documentId);

        SpaceMember member = SpaceContext.get();
        permissionHelper.checkOwnerOrCreator(member, doc.getUploadBy(), loginUser.getUserId());

        LambdaQueryWrapper<DocumentTag> lqw = new LambdaQueryWrapper<>();
        lqw.eq(DocumentTag::getDocumentId, documentId);
        lqw.eq(DocumentTag::getTagId, tagId);
        documentTagMapper.delete(lqw);
    }

    @Override
    @RequireSpaceRole
    public List<Document> listDocumentsByTag(Long spaceId, Long tagId, LoginUser loginUser) {
        Tag tag  = tagMapper.selectById(tagId);
        if (tag == null) {
            throw new BusinessException("标签不存在");
        }

        if (!tag.getSpaceId().equals(spaceId)) {
            throw new BusinessException("标签不属于当前空间");
        }

        return documentMapper.listDocumentsByTag(spaceId, tagId);
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


}
