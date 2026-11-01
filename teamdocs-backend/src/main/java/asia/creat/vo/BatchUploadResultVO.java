package asia.creat.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchUploadResultVO {
    private int totalCount;
    private int successCount;
    private int failCount;
    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String fileName;
        private Long documentId;
        private boolean success;
        private String errorMessage;
    }
}
