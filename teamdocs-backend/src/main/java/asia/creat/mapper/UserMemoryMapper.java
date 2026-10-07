package asia.creat.mapper;

import asia.creat.memory.UserMemoryData.Row;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMemoryMapper extends BaseMapper<Row> {
}
