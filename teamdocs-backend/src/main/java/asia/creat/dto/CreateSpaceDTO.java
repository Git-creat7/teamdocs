package asia.creat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateSpaceDTO {
    @NotBlank(message = "空间名称不能为空")
    @Size(min = 1, max = 64, message = "空间名称长度1-64")
    private String name;
    @Size(max = 255,message = "空间描述最多255字符")
    //DB 列是 VARCHAR(64)，如果用户传 100 个字符，没有应用层校验的话，
    //MySQL 严格模式下直接抛异常，宽松模式下静默截断（更糟）。
    //在 DTO 这一层挡掉，错误信息也更友好。
    private String description;
}
