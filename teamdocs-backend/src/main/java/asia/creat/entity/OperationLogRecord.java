package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("operation_log")
public class OperationLogRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long spaceId;
    private String operationName;
    private String resourceType;
    private Long resourceId;
    private String resourceName;
    private String methodName;
    private String requestMethod;
    private String requestUri;
    private Integer success;
    private String errorMessage;
    private Long durationMs;
    private LocalDateTime createdAt;
}
