package asia.creat.mapper;

import asia.creat.entity.Space;
import asia.creat.vo.SpaceListItemVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SpaceMapper extends BaseMapper<Space> {
    List<SpaceListItemVO> selectMySpacesWithMeta(@Param("userId") Long userId);
}
