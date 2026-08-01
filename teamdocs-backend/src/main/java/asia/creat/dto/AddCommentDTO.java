package asia.creat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddCommentDTO {
    @NotBlank(message = "评论内容不能为空")
    @Size(max=1000, message = "评论长度必须在1到1000之间")
    private String content;

    @Positive(message = "该评论不存在或已被删除，无法回复")
    private Long replyToId;
}
