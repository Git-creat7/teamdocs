package asia.creat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRegisterDTO {
    @NotBlank(message = "账号不能为空")
    @Size(min=2, max=16, message = "账号长度必须在2-16之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min=6, max=20, message = "密码长度必须在6-20之间")
    private String password;
}
