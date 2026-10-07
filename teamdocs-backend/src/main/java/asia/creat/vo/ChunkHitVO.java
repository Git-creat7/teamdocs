package asia.creat.vo;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

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
    @JsonIgnore
    private String imageRef;
    private String imageLabel;

    /** 对外只说明是否可查看原图，不暴露原件内部路径。 */
    public boolean isImageSource() {
        return imageRef != null && !imageRef.isBlank();
    }

    /** 可选的 HTML 转义高亮；不替代正文，也不改变来源偏移。 */
    private String highlight;
}
