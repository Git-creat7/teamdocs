package asia.creat.dto;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
public class MoveDocumentDTO {
    @NotNull(message = "目标目录ID不能为空！")
    private Long targetFolderId; // 目标目录ID，0表示移动到根目录
}
