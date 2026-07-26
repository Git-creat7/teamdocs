package asia.creat.mapper;

import asia.creat.entity.Tag;
import asia.creat.vo.DocumentTagRelVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TagMapper extends BaseMapper<Tag> {
    /*
     * 查单文档已打的标签
     * */
    List<Tag> selectTagsByDocumentId(@Param("documentId") Long documentId);

    /*
     * 批量查多文档标签 (JOIN document 过滤空间归属与软删除，越权 id 自动滤掉)
     * */
    List<DocumentTagRelVO> selectTagsByDocumentIds(@Param("spaceId") Long spaceId,
                                                   @Param("documentIds") List<Long> documentIds);
}
