package asia.creat.mapper;

import asia.creat.entity.ChatAttachment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

@Mapper
public interface ChatAttachmentMapper extends BaseMapper<ChatAttachment> {
    List<ChatAttachment> expired();
}
