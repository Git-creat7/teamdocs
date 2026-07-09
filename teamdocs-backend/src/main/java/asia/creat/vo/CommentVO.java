package asia.creat.vo;

import lombok.Data;

@Data
public class CommentVO {
    private Long id;
    private String content;
    private String createdAt;
    private Long userId;
    private String userName;
    private Long replyToId;
    private String replyToUserName;
    private Integer deleted;
}
