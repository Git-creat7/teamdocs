package asia.creat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSpaceDTO {
    @NotBlank(message = "空间名称不能为空")
    @Size(min = 1, max = 64, message = "空间名称长度1-64")
    private String name;
    @Size(max = 255, message = "空间描述最多255字符")
    private String description;
}
