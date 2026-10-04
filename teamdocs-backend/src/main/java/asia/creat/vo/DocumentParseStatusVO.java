package asia.creat.vo;

import asia.creat.entity.ParseStatus;
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
public class DocumentParseStatusVO {
    private Long documentId;
    private ParseStatus parseStatus;
    private Integer chunkCount;
    private String parseError;
    private LocalDateTime parsedAt;
    private Integer parseVersion;
}
