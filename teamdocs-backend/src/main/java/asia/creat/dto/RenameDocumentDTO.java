package asia.creat.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class RenameDocumentDTO {
    @NotBlank(message = "文档名不能为空！")
    private String newName;
}
