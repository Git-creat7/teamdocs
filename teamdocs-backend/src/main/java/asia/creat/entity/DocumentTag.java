package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@TableName("document_tag")
public class DocumentTag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Long tagId;
}
