package asia.creat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MoveFolderDTO {
    @NotNull(message = "目标目录ID不能为空！")
    private Long targetParentId; // 目标目录ID，0表示移动到根目录
}
