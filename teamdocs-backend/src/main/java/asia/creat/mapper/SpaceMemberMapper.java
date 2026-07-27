package asia.creat.mapper;

import asia.creat.entity.SpaceMember;
import asia.creat.vo.SpaceMemberVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SpaceMemberMapper extends BaseMapper<SpaceMember> {

    List<SpaceMemberVO> listMembers(Long spaceId);
}
