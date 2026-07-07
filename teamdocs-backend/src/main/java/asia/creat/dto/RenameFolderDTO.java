package asia.creat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RenameFolderDTO {
    @NotBlank(message = "目录名不能为空！")
    private String newName;
}
