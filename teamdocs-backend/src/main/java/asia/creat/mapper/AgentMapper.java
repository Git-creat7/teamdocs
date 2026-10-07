package asia.creat.mapper;

import asia.creat.agent.AgentData.Run;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface AgentMapper extends BaseMapper<Run> {
    /** 读取运行及对应的用户提问。 */
    Run run(@Param("id") Long id);

    /** 锁定运行记录，保留单条查询超时。 */
    @Select("SELECT * FROM agent_run WHERE id=#{id} FOR UPDATE")
    @Options(timeout = 5)
    Run lockRun(@Param("id") Long id);
}
