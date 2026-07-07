package asia.creat.service;

import asia.creat.dto.AddMemberDTO;
import asia.creat.dto.CreateSpaceDTO;
import asia.creat.dto.UpdateMemberRoleDTO;
import asia.creat.dto.UpdateSpaceDTO;
import asia.creat.entity.Space;
import asia.creat.security.LoginUser;
import asia.creat.vo.SpaceMemberVO;

import java.util.List;

public interface SpaceService {
    void createSpace(CreateSpaceDTO dto, LoginUser loginUser);

    List<Space> listMySpaces(LoginUser loginUser);

    Space getSpaceById(Long id, LoginUser loginUser);

    void deleteSpace(Long spaceId, LoginUser loginUser);

    void updateSpace(Long spaceId, UpdateSpaceDTO dto, LoginUser loginUser);

    void addMember(Long spaceId, AddMemberDTO dto, LoginUser loginUser);

    List<SpaceMemberVO> listMembers(Long spaceId, LoginUser loginUser);

    void removeMember(Long spaceId, Long targetUserId, LoginUser loginUser);

    void updateMemberRole(Long spaceId, Long targetUserId, UpdateMemberRoleDTO dto, LoginUser loginUser);
}
