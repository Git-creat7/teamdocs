package asia.creat.teamdocsbackend.service.impl;

import asia.creat.entity.Document;
import asia.creat.entity.Folder;
import asia.creat.entity.SpaceMember;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.FolderMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.impl.FolderServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FolderServiceImplTest {
    private static final Long SPACE_ID = 1L;
    private static final Long USER_ID = 7L;
    private static final LoginUser LOGIN_USER = new LoginUser(USER_ID, "alice");

    @Mock
    private FolderMapper folderMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private ResourcePermissionHelper permissionHelper;

    private FolderServiceImpl service;

    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Folder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Document.class);
    }

    @BeforeEach
    void setUp() {
        service = new FolderServiceImpl(folderMapper, documentMapper, permissionHelper);
        SpaceContext.set(new SpaceMember());
    }

    @AfterEach
    void tearDown() {
        SpaceContext.clear();
    }

    @Test
    void deleteFolderShouldLimitSubtreeAndDocumentsToCurrentSpace() {
        Folder folder = new Folder();
        folder.setId(10L);
        folder.setSpaceId(SPACE_ID);
        folder.setCreatedBy(USER_ID);
        when(folderMapper.selectById(10L)).thenReturn(folder);
        when(folderMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.deleteFolder(SPACE_ID, 10L, LOGIN_USER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Folder>> folderQueryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(folderMapper).selectList(folderQueryCaptor.capture());
        assertTrue(folderQueryCaptor.getValue().getSqlSegment().contains("space_id"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Document>> documentQueryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(documentMapper).delete(documentQueryCaptor.capture());
        assertTrue(documentQueryCaptor.getValue().getSqlSegment().contains("space_id"));
        verify(folderMapper).deleteByIds(List.of(10L));
    }
}
