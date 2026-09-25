package asia.creat.vo;

import asia.creat.entity.ParseStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentParseStatusVO {
    private Long documentId;
    private ParseStatus parseStatus;
    private Integer chunkCount;
    private String parseError;
    private LocalDateTime parsedAt;
    private Integer parseVersion;
}
