package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@TableName("space_member")
public class SpaceMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spaceId;
    private Long userId;
    private SpaceRole role;
    private LocalDateTime joinedAt;
}
