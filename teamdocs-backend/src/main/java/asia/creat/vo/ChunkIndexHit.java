package asia.creat.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChunkIndexHit {
    private Long chunkId;
    private Long documentId;
    private Integer parseVersion;
    private String highlight;
}
