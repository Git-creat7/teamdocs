package asia.creat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateFolderDTO {
    @NotBlank(message = "目录名不能为空！")
    private String name;
    private Long parentId; // 可选，默认为根目录
}
