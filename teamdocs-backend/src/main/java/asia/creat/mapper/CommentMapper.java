package asia.creat.mapper;

import asia.creat.entity.Comment;
import asia.creat.vo.CommentVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
    /*
    * 根据文档ID查询评论列表
    * */
    IPage<CommentVO> listByDocumentId(IPage<CommentVO> page, @Param("documentId") Long documentId);
}
