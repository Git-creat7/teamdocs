package asia.creat.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class RenameFolderDTO {
    @NotBlank(message = "目录名不能为空！")
    private String newName;
}
