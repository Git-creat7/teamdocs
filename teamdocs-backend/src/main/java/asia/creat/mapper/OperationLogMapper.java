package asia.creat.mapper;

import asia.creat.entity.OperationLogRecord;
import asia.creat.vo.ActivityVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLogRecord> {

    List<ActivityVO> selectRecentActivities(@Param("userId") Long userId, @Param("spaceId") Long spaceId, @Param("limit") Integer limit);
}
