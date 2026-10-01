package asia.creat.vo;

import asia.creat.entity.ParseStatus;
import lombok.Data;

import java.util.List;

@Data
public class ChunkReadVO {
    private boolean available;
    private boolean hasMore;
    // 不可读时告诉调用方是还没解析好、失败还是跳过，不能只给一个空列表
    private ParseStatus parseStatus;
    private Integer parseVersion;
    private String documentName;
    private List<ChunkHitVO> chunks;
}
