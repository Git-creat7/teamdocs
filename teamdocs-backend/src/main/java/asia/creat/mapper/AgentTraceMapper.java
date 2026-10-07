package asia.creat.mapper;

import asia.creat.agent.AgentData.Trace;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentTraceMapper extends BaseMapper<Trace> {
    int deleteSessionToolCalls(@Param("sessionId") Long sessionId);
}
