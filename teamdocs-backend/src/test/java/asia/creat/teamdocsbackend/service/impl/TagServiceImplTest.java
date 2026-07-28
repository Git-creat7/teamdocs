package asia.creat.teamdocsbackend.service.impl;

import asia.creat.common.exception.BusinessException;
import asia.creat.entity.Tag;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.DocumentTagMapper;
import asia.creat.mapper.TagMapper;
import asia.creat.security.LoginUser;
import asia.creat.service.impl.TagServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagServiceImplTest {
    private static final Long SPACE_ID = 1L;
    private static final Long TAG_ID = 2L;
    private static final LoginUser LOGIN_USER = new LoginUser(7L, "alice");

    @Mock
    private TagMapper tagMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private ResourcePermissionHelper permissionHelper;

    @Mock
    private DocumentTagMapper documentTagMapper;

    private TagServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TagServiceImpl(tagMapper, documentMapper, permissionHelper, documentTagMapper);
    }

    @Test
    void deleteTagShouldRemoveRelationsBeforeTag() {
        Tag tag = new Tag();
        tag.setId(TAG_ID);
        tag.setSpaceId(SPACE_ID);
        when(tagMapper.selectById(TAG_ID)).thenReturn(tag);

        service.deleteTag(SPACE_ID, TAG_ID, LOGIN_USER);

        InOrder ordered = inOrder(tagMapper, documentTagMapper);
        ordered.verify(tagMapper).selectById(TAG_ID);
        ordered.verify(documentTagMapper).delete(any());
        ordered.verify(tagMapper).delete(any());
    }

    @Test
    void deleteTagShouldKeepRelationsWhenTagDoesNotExist() {
        assertThrows(BusinessException.class, () -> service.deleteTag(SPACE_ID, TAG_ID, LOGIN_USER));

        verify(documentTagMapper, never()).delete(any());
        verify(tagMapper, never()).delete(any());
    }
}
