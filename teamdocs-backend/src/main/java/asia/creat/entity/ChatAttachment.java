package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("agent_attachment")
public class ChatAttachment {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private Long spaceId;
    private Long runId;
    private String name;
    private String mime;
    private long size;
    private String objectKey;
    private LocalDateTime createdAt;
}
