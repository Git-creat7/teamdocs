package asia.creat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RenameDocumentDTO {
    @NotBlank(message = "文档名不能为空！")
    private String newName;
}
