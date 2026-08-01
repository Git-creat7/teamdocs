package asia.creat.mapper;

import asia.creat.entity.Space;
import asia.creat.vo.SpaceListItemVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SpaceMapper extends BaseMapper<Space> {
    /*
     * 我的空间列表：一次 JOIN 聚合带出我的角色、成员数、未删文档数
     * */
    List<SpaceListItemVO> selectMySpacesWithMeta(@Param("userId") Long userId);
}
