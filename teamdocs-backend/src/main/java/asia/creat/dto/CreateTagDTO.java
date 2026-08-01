package asia.creat.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class CreateTagDTO {
    @NotBlank(message = "标签名称不能为空")
    private String name;
}
