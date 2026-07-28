package asia.creat.vo;

import lombok.Data;

@Data
public class DocumentPreviewVO {
    private Long documentId;
    private String name;
    private String fileType;
    private Long fileSize;
    private String url;
}
