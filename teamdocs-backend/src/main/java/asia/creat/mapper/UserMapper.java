package asia.creat.mapper;

import asia.creat.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    /** 锁定有效用户，串行化问答提交、会话删除和记忆修改。 */
    @Select("SELECT id FROM user WHERE id=#{userId} AND status=1 FOR UPDATE")
    @Options(timeout = 5)
    Long lockActiveUser(@Param("userId") Long userId);
}
