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
    /*
     * 查回收站列表
     * */
    IPage<Document> selectTrashedDocuments(IPage<Document> page,
                                           @Param("spaceId") Long spaceId);

    /*
     * 查软删除的文件
     * */
    Document selectDeletedDocument(Long documentId);

    /*
     * 彻底删除
     * */
    boolean purgeDeleteById(Long documentId);

    void updateDeleted(Long documentId, Long FolderId);

    /*
     * 通过Tag获取文档列表
     * */
    IPage<Document> listDocumentsByTag(IPage<Document> page,
                                       @Param("spaceId") Long spaceId,
                                       @Param("tagId") Long tagId);

    /*
    * 搜索name + description + tag
    * */
    IPage<Document> searchDocuments(IPage<Document> page,
                                    @Param("spaceId") Long spaceId,
                                    @Param("keyword") String keyword);

    /*
    * 批量查询最近文档
    * */
    List<RecentDocumentVO> listAccessibleRecentDocuments(@Param("userId") Long userId,
                                                         @Param("documentIds") List<Long> documentIds);

    /*
    * 查询包括删除的文档
    * */
    String selectNameIncludingDeleted(@Param("spaceId") Long spaceId,
                                      @Param("documentId") Long documentId);
}
