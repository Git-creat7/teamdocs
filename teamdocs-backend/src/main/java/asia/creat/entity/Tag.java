package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@TableName("tag")
public class Tag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long spaceId;
    private String name;
    private LocalDateTime createdAt;
}
