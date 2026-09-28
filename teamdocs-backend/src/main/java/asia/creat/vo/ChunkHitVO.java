package asia.creat.vo;

import lombok.Data;

@Data
public class ChunkHitVO {
    private Long chunkId;
    private Long documentId;
    private Integer chunkIndex;
    private Integer parseVersion;
    private String documentName;
    private Integer pageNumber;
    private Integer charStart;
    private Integer charEnd;
    private String excerpt;
}
