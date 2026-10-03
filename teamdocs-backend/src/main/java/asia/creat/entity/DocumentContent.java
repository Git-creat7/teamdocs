package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@TableName("document_content")
public class DocumentContent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Long spaceId;
    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;
    // PDF 从 1 开始；TXT/MD/DOCX 为空。偏移相对该段提取文本，不是原文件字节
    private Integer pageNumber;
    private Integer charStart;
    private Integer charEnd;
    /** 原图在原文件内的稳定定位，不保存临时访问 URL。 */
    private String imageRef;
    private String imageLabel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
