package asia.creat.mapper;

import asia.creat.entity.Document;
import asia.creat.vo.RecentDocumentVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
    IPage<Document> selectTrashedDocuments(IPage<Document> page,
                                           @Param("spaceId") Long spaceId);

    Document selectDeletedDocument(Long documentId);

    boolean purgeDeleteById(Long documentId);

    void updateDeleted(Long documentId, Long FolderId);

    IPage<Document> listDocumentsByTag(IPage<Document> page,
                                       @Param("spaceId") Long spaceId,
                                       @Param("tagId") Long tagId);

    IPage<Document> searchDocuments(IPage<Document> page,
                                    @Param("spaceId") Long spaceId,
                                    @Param("keyword") String keyword);

    List<RecentDocumentVO> listAccessibleRecentDocuments(@Param("userId") Long userId,
                                                         @Param("documentIds") List<Long> documentIds);

    String selectNameIncludingDeleted(@Param("spaceId") Long spaceId,
                                      @Param("documentId") Long documentId);
}
