package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@TableName("document")
public class Document {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spaceId;
    private Long folderId;
    private String name;
    private String fileType;
    private Long fileSize;
    private String filePath;
    private String description;
    private Long uploadBy;
    @TableLogic
    private Integer deleted;
    private ParseStatus parseStatus;
    private Integer chunkCount;
    private String parseError;
    private LocalDateTime parsedAt;
    private Integer parseVersion;
    private LocalDateTime parseStartedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
