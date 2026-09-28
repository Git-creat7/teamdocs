package asia.creat.vo;

import lombok.Data;

@Data
public class ChunkCitationVO {
    private boolean accessible;
    /** 不可访问时的说明。可访问时为空 */
    private String message;
    private ChunkHitVO chunk;
}
