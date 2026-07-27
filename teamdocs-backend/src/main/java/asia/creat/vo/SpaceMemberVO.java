package asia.creat.vo;

import asia.creat.entity.SpaceRole;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SpaceMemberVO {
    private Long id;
    private Long spaceId;
    private Long userId;
    private String username;
    private String avatar;
    private SpaceRole role;
    private LocalDateTime joinedAt;
}
