package asia.creat.mapper;

import asia.creat.entity.Comment;
import asia.creat.vo.CommentVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
    /*
    * 根据文档ID查询评论列表
    * */
    List<CommentVO> listByDocumentId(Long documentId);
}
