package asia.creat.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RestoreDocumentVO {
    private Long folderId;
    private Boolean originalFolderDeleted;
}
