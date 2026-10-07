package asia.creat.mapper;

import asia.creat.agent.AgentData.Message;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AgentMessageMapper extends BaseMapper<Message> {
    List<Message> messages(@Param("sessionId") Long sessionId, @Param("offset") long offset, @Param("limit") long limit);

    Message answer(@Param("runId") Long runId);

    List<Message> history(@Param("sessionId") Long sessionId, @Param("beforeId") long beforeId, @Param("limit") int limit);
}
