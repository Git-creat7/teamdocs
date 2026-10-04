package asia.creat.vo;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DocumentPreviewVO {
    private Long documentId;
    private String name;
    private String fileType;
    private Long fileSize;
    private String url;
}
