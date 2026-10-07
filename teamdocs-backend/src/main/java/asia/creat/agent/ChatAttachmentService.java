package asia.creat.agent;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.BucketType;
import asia.creat.common.exception.BusinessException;
import asia.creat.entity.ChatAttachment;
import asia.creat.mapper.ChatAttachmentMapper;
import asia.creat.mapper.UserMapper;
import asia.creat.security.LoginUser;
import asia.creat.service.FileStorageService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatAttachmentService extends ServiceImpl<ChatAttachmentMapper, ChatAttachment> {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private final FileStorageService storage;
    private final UserMapper users;
    private final AgentRepository runs;
    private final AttachmentContentReader contentReader;

    public record View(String id, String name, String mime, long size) { }

    /** 附件只存私有桶，单文件5MB，每用户最多20个临时附件。 */
    @Transactional
    @RequireSpaceRole
    public View upload(@SpaceId Long spaceId, MultipartFile file, LoginUser user) throws IOException {
        if (users.lockActiveUser(user.getUserId()) == null) throw new BusinessException("用户不可用");
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw new BusinessException("附件不能为空且每个不能超过5MB");
        if (lambdaQuery().eq(ChatAttachment::getUserId, user.getUserId()).isNull(ChatAttachment::getRunId).count() >= 20) {
            throw new BusinessException("临时附件过多，请移除未使用附件后重试");
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 160 || name.contains("/") || name.contains("\\")
                || name.codePoints().anyMatch(Character::isISOControl)) throw new BusinessException("附件名称无效");
        byte[] bytes;
        try (var input = file.getInputStream()) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new BusinessException("附件不能超过5MB");
        String mime = validate(name, bytes);
        ChatAttachment row = new ChatAttachment();
        row.setId(UUID.randomUUID().toString());
        row.setSpaceId(spaceId);
        row.setUserId(user.getUserId());
        row.setName(name);
        row.setMime(mime);
        row.setSize(bytes.length);
        row.setCreatedAt(LocalDateTime.now());
        row.setObjectKey("chat-attachments/" + user.getUserId() + "/" + row.getId());
        storage.upload(file, BucketType.PRIVATE, row.getObjectKey());
        try { save(row); }
        catch (RuntimeException error) { storage.delete(BucketType.PRIVATE, row.getObjectKey()); throw error; }
        return view(row);
    }

    /** 单纯校验文件，不提取正文，不调用视觉或解析服务。 */
    static String validate(String name, byte[] data) throws IOException {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime = switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt", "md", "csv", "tsv" -> "text/plain";
            case "json", "jsonl" -> "application/json";
            default -> throw new BusinessException("支持图片、PDF、DOCX、XLSX、PPTX、TXT、Markdown、JSON、CSV 附件");
        };
        if (mime.startsWith("image/")) {
            try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new BusinessException("图片格式无效或当前服务无法验证该图片");
                var reader = readers.next();
                try {
                    reader.setInput(stream);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!(format.equals(ext) || (format.equals("jpeg") && ext.equals("jpg")))) throw new BusinessException("图片内容与扩展名不符");
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > 16_000_000L) throw new BusinessException("图片不能超过1600万像素");
                } finally { reader.dispose(); }
            }
        } else if (ext.equals("pdf")) {
            if (data.length < 5 || !new String(data, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
                throw new BusinessException("PDF 文件格式无效");
            }
        } else if (List.of("docx", "xlsx", "pptx").contains(ext)) {
            if (data.length < 4 || data[0] != 'P' || data[1] != 'K') throw new BusinessException("Office 文件格式无效或已加密");
        } else {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data));
        }
        return mime;
    }

    /** 调用方持有用户锁，与创建运行同事务绑定附件。 */
    public void bind(AgentData.Run run, List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        if (ids.size() > 4 || new HashSet<>(ids).size() != ids.size()) throw new BusinessException("每次最多4个附件，不能重复");
        for (String id : ids) {
            ChatAttachment row = require(run.getSpaceId(), id, run.getUserId());
            if (row.getRunId() != null || row.getCreatedAt().isBefore(LocalDateTime.now().minusDays(1))) throw new BusinessException("附件已使用或已过期，请重新上传");
            if (!lambdaUpdate().eq(ChatAttachment::getId, id).isNull(ChatAttachment::getRunId)
                    .set(ChatAttachment::getRunId, run.getId()).update()) throw new BusinessException("附件状态已变化");
        }
    }

    public boolean has(Long runId) { return lambdaQuery().eq(ChatAttachment::getRunId, runId).exists(); }

    /** 只把当前运行附件交给模型，不自动回放旧附件或推导全局记忆。 */
    public List<AttachmentMessage.Part> parts(AgentData.Run run) throws IOException {
        var rows = lambdaQuery().eq(ChatAttachment::getRunId, run.getId()).orderByAsc(ChatAttachment::getId).list();
        if (rows.size() > 4) throw new BusinessException("附件数量异常");
        List<AttachmentMessage.Part> result = new ArrayList<>();
        for (ChatAttachment row : rows) {
            if (!row.getUserId().equals(run.getUserId()) || !row.getSpaceId().equals(run.getSpaceId())) throw new BusinessException("附件不可访问");
            byte[] bytes = bytes(row);
            try {
                result.addAll(contentReader.read(row.getName(), row.getMime(), bytes));
            } catch (IOException error) {
                throw new AgentFailure("ATTACHMENT_CONTENT_UNAVAILABLE");
            }
        }
        return result;
    }

    @RequireSpaceRole
    public List<View> list(@SpaceId Long spaceId, Long runId, LoginUser user) {
        var run = runs.run(runId);
        if (run == null || !run.getSpaceId().equals(spaceId) || !run.getUserId().equals(user.getUserId())) throw new BusinessException("会话不可访问");
        return lambdaQuery().eq(ChatAttachment::getRunId, runId).list().stream().map(this::view).toList();
    }

    @RequireSpaceRole
    public byte[] read(@SpaceId Long spaceId, String id, LoginUser user) throws IOException {
        return bytes(require(spaceId, id, user.getUserId()));
    }

    @Transactional
    @RequireSpaceRole
    public void remove(@SpaceId Long spaceId, String id, LoginUser user) {
        if (users.lockActiveUser(user.getUserId()) == null) throw new BusinessException("用户不可用");
        ChatAttachment row = require(spaceId, id, user.getUserId());
        if (row.getRunId() != null) throw new BusinessException("已发送附件随会话删除，不能单独移除");
        storage.delete(BucketType.PRIVATE, row.getObjectKey());
        removeById(id);
    }

    /** 未发送附件一天后清理；会话删除后的附件由持久记录重试清理。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000, scheduler = "userMemoryTaskScheduler")
    public void cleanup() {
        try {
            for (ChatAttachment row : baseMapper.expired()) {
                try {
                    storage.delete(BucketType.PRIVATE, row.getObjectKey());
                    removeById(row.getId());
                } catch (RuntimeException error) {
                    log.warn("对话附件清理失败，将稍后重试");
                }
            }
        } catch (RuntimeException error) { log.warn("对话附件清理不可用，请检查数据库迁移"); }
    }

    private ChatAttachment require(Long space, String id, Long user) {
        ChatAttachment row = getById(id);
        if (row == null || !row.getUserId().equals(user) || !row.getSpaceId().equals(space)) throw new BusinessException("附件不可访问");
        if (row.getRunId() != null) {
            var run = runs.run(row.getRunId());
            if (run == null || !run.getUserId().equals(user) || !run.getSpaceId().equals(space)) throw new BusinessException("会话不可访问");
        }
        return row;
    }
    private byte[] bytes(ChatAttachment row) throws IOException {
        try (var input = storage.open(BucketType.PRIVATE, row.getObjectKey())) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length != row.getSize() || bytes.length > MAX_BYTES) throw new BusinessException("附件已变化或超过限制");
            return bytes;
        }
    }
    private View view(ChatAttachment row) { return new View(row.getId(), row.getName(), row.getMime(), row.getSize()); }
}
