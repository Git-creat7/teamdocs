package asia.creat.dto;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
public class MoveFolderDTO {
    @NotNull(message = "目标目录ID不能为空！")
    private Long targetParentId; // 目标目录ID，0表示移动到根目录
}
