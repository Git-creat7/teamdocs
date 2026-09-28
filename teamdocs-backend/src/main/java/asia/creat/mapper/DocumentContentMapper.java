package asia.creat.mapper;

import asia.creat.entity.DocumentContent;
import asia.creat.vo.ChunkHitVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentContentMapper extends BaseMapper<DocumentContent> {

    List<ChunkHitVO> searchChunks(@Param("spaceId") Long spaceId,
                                  @Param("keyword") String keyword,
                                  @Param("limit") int limit);

    List<ChunkHitVO> readChunks(@Param("spaceId") Long spaceId,
                                @Param("documentId") Long documentId,
                                @Param("startIndex") int startIndex,
                                @Param("limit") int limit);

    ChunkHitVO findReadableChunk(@Param("spaceId") Long spaceId,
                                 @Param("documentId") Long documentId,
                                 @Param("chunkId") Long chunkId,
                                 @Param("parseVersion") Integer parseVersion);
}
