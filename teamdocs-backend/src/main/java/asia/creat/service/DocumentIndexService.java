package asia.creat.service;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.EmbeddingProperties;
import asia.creat.config.MilvusProperties;
import asia.creat.entity.*;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.SpaceMapper;
import asia.creat.mapper.SpaceMemberMapper;
import asia.creat.mapper.VectorIndexMapper;
import asia.creat.retrieval.VectorIndexQueue;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaQueryChain;
import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaUpdateChain;

@Service
@RequiredArgsConstructor
public class DocumentIndexService {
    private final DocumentMapper documents;
    private final DocumentContentMapper contents;
    private final SpaceMemberMapper members;
    private final SpaceMapper spaces;
    private final ChunkIndex es;
    private final VectorIndexMapper vectors;
    private final Optional<VectorIndexQueue> queue;
    private final EmbeddingProperties embedding;
    private final MilvusProperties milvus;
    private final Semaphore repairSlots = new Semaphore(1);

    public record Status(Long documentId, String parseStatus, long chunks, boolean canRepair,
                         ChunkIndex.DocumentStatus elasticsearch, String vectorStatus, String vectorDetail) { }

    @RequireSpaceRole
    public Status status(@SpaceId Long spaceId, Long documentId, LoginUser user) {
        Document before = requireDocument(spaceId, documentId);
        Status result = snapshot(before);
        Document after = requireDocument(spaceId, documentId);
        if (!Objects.equals(before.getParseVersion(), after.getParseVersion())
                || before.getParseStatus() != after.getParseStatus()) throw new BusinessException("文档正在更新，请刷新索引状态");
        return result;
    }

    /** 只同步一个文档，不清空集合或影响其他空间；向量复用持久化待办。 */
    @RequireSpaceRole({SpaceRole.OWNER, SpaceRole.ADMIN})
    public Status repair(@SpaceId Long spaceId, Long documentId, String target, LoginUser user) {
        Document document = requireDocument(spaceId, documentId);
        if (document.getParseStatus() != ParseStatus.READY) throw new BusinessException("正文尚未解析完成，请先完成解析");
        if (!repairSlots.tryAcquire()) throw new BusinessException("正在处理索引修复，请稍后重试");
        try {
            if ("es".equals(target)) {
                if (!es.enabled()) throw new BusinessException("Elasticsearch 未启用");
                try { es.syncDocument(documentId); }
                catch (RuntimeException error) { throw new BusinessException("ES 同步失败，请检查服务连接、IK 分词插件及索引配置"); }
            } else if ("vector".equals(target)) {
                if (!vectorEnabled()) throw new BusinessException("向量化尚未配置、启用或授权");
                queue.orElseThrow().enqueue(documentId);
                lambdaUpdateChain(vectors).eq(VectorIndexMapper.Task::getDocumentId, documentId)
                        .setIncrBy(VectorIndexMapper.Task::getGeneration, 1).set(VectorIndexMapper.Task::getState, "PENDING")
                        .set(VectorIndexMapper.Task::getAttempts, 0).set(VectorIndexMapper.Task::getNextAttemptMs, 0)
                        .set(VectorIndexMapper.Task::getErrorCode, null).update();
            } else throw new BusinessException("仅支持 ES 或向量索引修复");
            if (spaces.selectById(spaceId) == null || !lambdaQueryChain(members)
                    .eq(SpaceMember::getSpaceId, spaceId).eq(SpaceMember::getUserId, user.getUserId())
                    .in(SpaceMember::getRole, SpaceRole.OWNER, SpaceRole.ADMIN).exists()) {
                throw new BusinessException("空间或管理员权限已失效");
            }
            return snapshot(requireDocument(spaceId, documentId));
        } finally { repairSlots.release(); }
    }

    private Status snapshot(Document document) {
        long count = lambdaQueryChain(contents).eq(DocumentContent::getDocumentId, document.getId()).count();
        var member = SpaceContext.getSpaceMember();
        boolean canRepair = member != null && (member.getRole() == SpaceRole.OWNER || member.getRole() == SpaceRole.ADMIN);
        var keyword = document.getParseStatus() == ParseStatus.READY
                ? es.documentStatus(document.getSpaceId(), document.getId(), document.getParseVersion(), count)
                : new ChunkIndex.DocumentStatus("WAITING_PARSE", 0, "等待正文解析完成");
        String status = "DISABLED", detail = "向量化未配置、未启用或未授权";
        if (vectorEnabled()) {
            var task = vectors.selectById(document.getId());
            status = task == null ? "MISSING" : task.getState();
            detail = task == null ? "尚未登记向量同步任务" : task.getErrorCode() == null
                    ? "DONE".equals(task.getState()) ? "当前任务已完成（任务状态，不代表实时探测 Milvus）" : "后台等待或处理中"
                    : "向量同步失败：" + task.getErrorCode() + "；尝试次数：" + task.getAttempts();
            if (task != null && !task.getTargetSignature().equals(queue.orElseThrow().signature(vectors.snapshot(document.getId())))) {
                status = "OUTDATED"; detail = "文档版本或向量配置已变化，等待同步";
            }
        }
        return new Status(document.getId(), document.getParseStatus().name(), count, canRepair, keyword, status, detail);
    }

    private boolean vectorEnabled() {
        return queue.isPresent() && milvus.isEnabled() && embedding.isEnabled() && embedding.isAllowDocumentEgress();
    }
    private Document requireDocument(Long spaceId, Long id) {
        Document document = documents.selectById(id);
        if (document == null || !spaceId.equals(document.getSpaceId())) throw new BusinessException("文档不存在或不属于当前空间");
        return document;
    }
}
