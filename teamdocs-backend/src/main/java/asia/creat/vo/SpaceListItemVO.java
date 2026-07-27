package asia.creat.vo;

import asia.creat.entity.SpaceRole;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SpaceListItemVO {
    private Long id;
    private String name;
    private String description;
    private Long ownerId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private SpaceRole myRole;
    private Long memberCount;
    private Long docCount;
}
