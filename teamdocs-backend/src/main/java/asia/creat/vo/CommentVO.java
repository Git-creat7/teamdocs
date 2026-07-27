package asia.creat.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CommentVO {
    private Long id;
    private String content;
    private LocalDateTime createdAt;
    private Long userId;
    private String userName;
    private Long replyToId;
    private String replyToUserName;
    private Integer deleted;
}
