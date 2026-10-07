package asia.creat.mapper;

import asia.creat.agent.AgentData.ModelCall;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentModelCallMapper extends BaseMapper<ModelCall> {
    int deleteSessionModelCalls(@Param("sessionId") Long sessionId);
}
