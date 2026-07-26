package asia.creat.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ActivityVO {
    private Long id;
    private Long userId;
    private String username;
    private String avatar;
    private String operationName;
    private String resourceType;
    private Long spaceId;
    private String spaceName;
    private String documentName;
    private String resourceName;
    private LocalDateTime createdAt;
}
