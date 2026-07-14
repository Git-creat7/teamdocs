package asia.creat.service.impl;

import asia.creat.anno.OperationLog;
import asia.creat.anno.OperationTarget;
import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.AddMemberDTO;
import asia.creat.dto.CreateSpaceDTO;
import asia.creat.dto.UpdateMemberRoleDTO;
import asia.creat.dto.UpdateSpaceDTO;
import asia.creat.entity.Space;
import asia.creat.entity.SpaceMember;
import asia.creat.entity.User;
import asia.creat.mapper.SpaceMapper;
import asia.creat.mapper.SpaceMemberMapper;
import asia.creat.mapper.UserMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.SpaceService;
import asia.creat.vo.SpaceMemberVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

import static asia.creat.entity.SpaceRole.ADMIN;
import static asia.creat.entity.SpaceRole.OWNER;

@Slf4j
@Service
public class SpaceServiceImpl implements SpaceService {
    private final UserMapper userMapper;
    private final SpaceMapper spaceMapper;
    private final SpaceMemberMapper spaceMemberMapper;

    public SpaceServiceImpl(UserMapper userMapper, SpaceMapper spaceMapper, SpaceMemberMapper spaceMemberMapper) {
        this.userMapper = userMapper;
        this.spaceMapper = spaceMapper;
        this.spaceMemberMapper = spaceMemberMapper;
    }

    @Override
    @Transactional
    @OperationLog(value = "创建空间", resourceType = "SPACE")
    public void createSpace(CreateSpaceDTO dto, LoginUser loginUser) {
        Space space = new Space();
        space.setName(dto.getName());
        space.setDescription(dto.getDescription());
        space.setOwnerId(loginUser.getUserId());
        spaceMapper.insert(space);

        SpaceMember spaceMember = new SpaceMember();
        spaceMember.setSpaceId(space.getId());
        spaceMember.setUserId(loginUser.getUserId());
        spaceMember.setRole(OWNER);
        spaceMemberMapper.insert(spaceMember);
    }

    @Override
    public List<Space> listMySpaces(LoginUser loginUser) {
        // 从 space_member 查出我加入的所有 space_id
        LambdaQueryWrapper<SpaceMember> lqw = new LambdaQueryWrapper<>();
        lqw.eq(SpaceMember::getUserId, loginUser.getUserId());
        List<SpaceMember> members = spaceMemberMapper.selectList(lqw);
        List<Long> spaceIds = members.stream()
                .map(SpaceMember::getSpaceId)
                .toList();
        // 根据space_id列表查出所有空间信息
        if (spaceIds.isEmpty())  return Collections.emptyList();
        return spaceMapper.selectByIds(spaceIds);
    }

    @Override
    public Space getSpaceById(Long spaceId, LoginUser loginUser) {
        Space space = checkSpaceOrThrow(spaceId);
        checkIsMember(spaceId, loginUser.getUserId());
        return space;
    }

    @Override
    @RequireSpaceRole(OWNER)
    @OperationLog(value = "删除空间", resourceType = "SPACE")
    public void deleteSpace(@SpaceId @OperationTarget Long spaceId, LoginUser loginUser) {
        spaceMapper.deleteById(spaceId);
    }

    @Override
    @RequireSpaceRole({OWNER,ADMIN})
    @OperationLog(value = "更新空间", resourceType = "SPACE")
    public void updateSpace(@SpaceId @OperationTarget Long spaceId, UpdateSpaceDTO dto, LoginUser loginUser) {
        LambdaQueryWrapper<Space> lqw = new LambdaQueryWrapper<Space>()
                .eq(Space::getId, spaceId);

        Space space = spaceMapper.selectOne(lqw);
        space.setName(dto.getName());
        space.setDescription(dto.getDescription());
        spaceMapper.updateById(space);
    }

    @Override
    @RequireSpaceRole({OWNER, ADMIN})
    @OperationLog(value = "添加空间成员", resourceType = "SPACE")
    public void addMember(@SpaceId @OperationTarget Long spaceId, AddMemberDTO dto, LoginUser loginUser) {
        if (dto.getRole() == OWNER) {
            throw new BusinessException("不能直接添加 OWNER");
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if(user == null) throw new BusinessException("用户不存在");
        Long count = spaceMemberMapper.selectCount(
                new LambdaQueryWrapper<SpaceMember>()
                .eq(SpaceMember::getSpaceId, spaceId)
                .eq(SpaceMember::getUserId, user.getId())
        );
        if(count > 0){
            throw new BusinessException("用户已经是该空间的成员");
        }
        SpaceMember m = new SpaceMember(null, spaceId, user.getId(), dto.getRole(), null);
        spaceMemberMapper.insert(m);
    }

    @Override
    public List<SpaceMemberVO> listMembers(Long spaceId, LoginUser loginUser) {
        checkSpaceOrThrow(spaceId);
        checkIsMember(spaceId, loginUser.getUserId());
        return spaceMemberMapper.listMembers(spaceId);
    }

    @Override
    @OperationLog(value = "移除空间成员", resourceType = "SPACE")
    @RequireSpaceRole({OWNER, ADMIN})
    public void removeMember(@SpaceId @OperationTarget Long spaceId, Long targetUserId, LoginUser loginUser) {
        SpaceMember spaceMember = spaceMemberMapper.selectOne(
                new LambdaQueryWrapper<SpaceMember>()
                        .eq(SpaceMember::getSpaceId, spaceId)
                        .eq(SpaceMember::getUserId, targetUserId)
        );
        if(spaceMember == null) {
            throw new BusinessException("目标用户不是该空间的成员");
        }
        if(spaceMember.getRole() == OWNER) {
            throw new BusinessException("不能移除 OWNER");
        }

        SpaceMember currentMember = SpaceContext.getSpaceMember();
        if(currentMember.getRole() == ADMIN && spaceMember.getRole() == ADMIN) {
            throw new BusinessException("管理员不能移除其他管理员");
        }

        spaceMemberMapper.deleteById(spaceMember.getId());
        log.info("移除了空间 {} 中的成员 {}", spaceId, targetUserId);
    }

    @Override
    @RequireSpaceRole({OWNER})
    @OperationLog(value = "修改空间成员角色", resourceType = "SPACE")
    public void updateMemberRole(@SpaceId @OperationTarget Long spaceId, Long targetUserId, UpdateMemberRoleDTO dto, LoginUser loginUser) {
        if(dto.getRole() == OWNER){
            throw new BusinessException("不能直接设置 OWNER");
        }
        if(targetUserId.equals(loginUser.getUserId())){
            throw new BusinessException("不能修改自己的角色");
        }
        LambdaQueryWrapper<SpaceMember> lqw = new LambdaQueryWrapper<>();
        lqw.eq(SpaceMember::getSpaceId, spaceId)
                .eq(SpaceMember::getUserId, targetUserId);
        SpaceMember member = spaceMemberMapper.selectOne(lqw);
        if(member == null) {
            throw new BusinessException("目标用户不是该空间的成员");
        }
        member.setRole(dto.getRole());
        spaceMemberMapper.updateById(member);
        log.info("修改了空间 {} 中用户 {} 的角色为 {}", spaceId, targetUserId, dto.getRole());
    }

    /*
    * 检查空间是否为空
    * */
    private Space checkSpaceOrThrow(Long spaceId) {
        LambdaQueryWrapper<Space> lqw = new LambdaQueryWrapper<>();
        lqw.eq(Space::getId, spaceId);
        Space space = spaceMapper.selectOne(lqw);
        if(space == null){
            throw new BusinessException("空间不存在");
        }
        return space;
    }

    /*
    * 校验是否为空间成员
    * */
    public void checkIsMember(Long spaceId, Long userId){
        LambdaQueryWrapper<SpaceMember> lqwUserId = new LambdaQueryWrapper<>();
        lqwUserId.eq(SpaceMember::getSpaceId, spaceId)
                .eq(SpaceMember::getUserId, userId);
        Long count = spaceMemberMapper.selectCount(lqwUserId);
        if(count == 0){
            throw new BusinessException("您不是该空间的成员");
        }
    }
}
