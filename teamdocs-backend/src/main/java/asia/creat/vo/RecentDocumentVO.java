package asia.creat.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RecentDocumentVO {
    private Long documentId;
    private Long spaceId;
    private String spaceName;
    private String name;
    private String fileType;
    private Long fileSize;
    private LocalDateTime updatedAt;
    private LocalDateTime lastViewedAt;
}
