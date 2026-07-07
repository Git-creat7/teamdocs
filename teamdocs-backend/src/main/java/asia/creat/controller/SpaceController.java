package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.AddMemberDTO;
import asia.creat.dto.CreateSpaceDTO;
import asia.creat.dto.UpdateMemberRoleDTO;
import asia.creat.dto.UpdateSpaceDTO;
import asia.creat.entity.Space;
import asia.creat.security.LoginUser;
import asia.creat.service.SpaceService;
import asia.creat.vo.SpaceMemberVO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/space")
public class SpaceController {
    private final SpaceService spaceService;

    public SpaceController(SpaceService spaceService) {
        this.spaceService = spaceService;
    }

    /*
    * 创建空间
    * */
    @PostMapping("")
    public Result createSpace(@RequestBody @Validated CreateSpaceDTO dto,
                              @AuthenticationPrincipal LoginUser loginUser) {
        spaceService.createSpace(dto,loginUser);
        return Result.success();
    }
    /*
    * 查看我的空间
    * */
    @GetMapping("/list")
    public Result listMySpaces(@AuthenticationPrincipal LoginUser loginUser) {
        List<Space> spaces = spaceService.listMySpaces(loginUser);
        return Result.success(spaces);
    }
    /*
    * 查单个空间
    * */
    @GetMapping("/{id}")
    public Result getSpace(@PathVariable("id") Long spaceId,
                           @AuthenticationPrincipal LoginUser loginUser) {
        Space space = spaceService.getSpaceById(spaceId,loginUser);
        return Result.success(space);
    }
    /*
    * 删除空间（软删除）
    * */
    @DeleteMapping("/{id}")
    public Result deleteSpace(@PathVariable("id") Long spaceId,
                              @AuthenticationPrincipal LoginUser loginUser) {
        spaceService.deleteSpace(spaceId, loginUser);
        return Result.success();
    }

    /*
    * 更新空间信息
    * */
    @PutMapping("/{id}")
    public Result updateSpace(@PathVariable("id") Long spaceId,
                              @RequestBody @Validated UpdateSpaceDTO dto,
                              @AuthenticationPrincipal LoginUser loginUser) {
        spaceService.updateSpace(spaceId, dto, loginUser);
        return Result.success();
     }
     // --- 以下为成员管理 ---
     /*
     * 添加成员
     * */
    @PostMapping("/{id}/members")
    public Result addMember(@PathVariable("id") Long spaceId,
                            @RequestBody @Validated AddMemberDTO dto,
                            @AuthenticationPrincipal LoginUser loginUser) {
        spaceService.addMember(spaceId, dto, loginUser);
        return Result.success();
    }
    /*
    * 查看空间成员列表
    * */
    @GetMapping("/{id}/members")
    public Result listMembers(@PathVariable("id") Long spaceId,
                              @AuthenticationPrincipal LoginUser loginUser) {
        List<SpaceMemberVO> members = spaceService.listMembers(spaceId, loginUser);
        return Result.success(members);
    }
    /*
    * 移除成员
    * */
    @DeleteMapping("/{id}/members/{userId}")
    public Result removeMember(@PathVariable("id") Long spaceId,
                               @PathVariable("userId") Long targetUserId,
                               @AuthenticationPrincipal LoginUser loginUser) {
        spaceService.removeMember(spaceId, targetUserId, loginUser);
        return Result.success();
     }
     /*
     * 修改成员身份
     * */
    @PutMapping("/{id}/members/{userId}")
    public Result updateMemberRole(@PathVariable("id") Long spaceId,
                                   @PathVariable("userId") Long targetUserId,
                                   @RequestBody @Validated UpdateMemberRoleDTO dto,
                                   @AuthenticationPrincipal LoginUser loginUser) {
        spaceService.updateMemberRole(spaceId, targetUserId, dto, loginUser);
        return Result.success();
    }

}
