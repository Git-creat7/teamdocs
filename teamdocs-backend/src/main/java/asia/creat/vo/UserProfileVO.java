package asia.creat.vo;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserProfileVO {
    private Long userId;
    private String username;
    private String nickname;
    private String email;
    private String avatar;
    private Integer status;
    private LocalDateTime createdAt;
}
