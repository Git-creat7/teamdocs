package asia.creat.mapper;

import asia.creat.entity.Document;
import asia.creat.vo.RecentDocumentVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {
    /*
     * 查回收站列表
     * */
    List<Document> selectTrashedDocuments(Long spaceId);

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
    List<Document> listDocumentsByTag(Long spaceId, Long tagId);

    /*
    * 搜索name + description + tag
    * */
    List<Document> searchDocuments(Long spaceId, String keyword);

    /*
    * 批量查询最近文档
    * */
    List<RecentDocumentVO> listAccessibleRecentDocuments(Long userId,List<Long> documentIds);
}
