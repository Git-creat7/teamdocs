package asia.creat.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileVO {
    private Long userId;
    private String username;
    private String nickname;
    private String email;
    private String avatar;
    private Integer status;
    private LocalDateTime createdAt;
}
