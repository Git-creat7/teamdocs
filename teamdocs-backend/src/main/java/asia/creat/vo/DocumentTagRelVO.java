package asia.creat.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentTagRelVO {
    private Long documentId;
    private Long id;
    private Long spaceId;
    private String name;
    private LocalDateTime createdAt;
}
