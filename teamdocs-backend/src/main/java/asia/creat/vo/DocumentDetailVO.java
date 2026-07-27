package asia.creat.vo;

import asia.creat.entity.Tag;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class DocumentDetailVO {
    private Long id;
    private Long spaceId;
    private Long folderId;
    private String name;
    private String fileType;
    private Long fileSize;
    private String description;
    private Long uploadBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<Tag> tags;
}
