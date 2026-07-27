package asia.creat.helper;

import asia.creat.common.exception.BusinessException;
import asia.creat.entity.SpaceMember;
import asia.creat.entity.SpaceRole;
import org.springframework.stereotype.Component;

@Component
public class ResourcePermissionHelper {
    /*
    * 用于检查当前用户是否是资源的创建者
    * OWNER/ADMIN 可以操作空间内的任意资源，MEMBER 只能操作自己创建的资源
    * */
    public void checkOwnerOrCreator(SpaceMember member, Long creatorId, Long currentUserId) {
        if (member == null) {
            throw new IllegalStateException("SpaceContext 未初始化，请检查 @RequireSpaceRole 注解");
        }

        if (member.getRole() == SpaceRole.MEMBER
                && !creatorId.equals(currentUserId)) {

            throw new BusinessException("没有权限操作该资源");
        }
    }

}
