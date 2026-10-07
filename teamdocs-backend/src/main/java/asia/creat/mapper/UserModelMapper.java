package asia.creat.mapper;

import asia.creat.model.UserModelData.Config;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserModelMapper extends BaseMapper<Config> { }
