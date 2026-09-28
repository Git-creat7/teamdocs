package asia.creat.vo;

import lombok.Data;

@Data
public class ChunkHitVO {
    private Long spaceId;
    private Long chunkId;
    private Long documentId;
    private Integer chunkIndex;
    private Integer parseVersion;
    private String documentName;
    private Integer pageNumber;
    private Integer charStart;
    private Integer charEnd;
    private String excerpt;
    /** 可选的 HTML 转义高亮；不替代正文，也不改变来源偏移。 */
    private String highlight;
}
