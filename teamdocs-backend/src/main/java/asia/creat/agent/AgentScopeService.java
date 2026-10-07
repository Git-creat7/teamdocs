package asia.creat.agent;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.exception.BusinessException;
import asia.creat.entity.Document;
import asia.creat.entity.Folder;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.FolderMapper;
import asia.creat.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaQueryChain;

@Service
@RequiredArgsConstructor
public class AgentScopeService {
    private final DocumentMapper documents;
    private final FolderMapper folders;

    public record Option(Long id, String name, Long parentId, String kind) { }

    /** 选择器按当前目录有界读取，不一次加载整个空间。 */
    @RequireSpaceRole
    public List<Option> options(@SpaceId Long spaceId, Long folderId, LoginUser user) {
        long parent = folderId == null ? 0 : folderId;
        if (parent != 0) requireFolder(spaceId, parent);
        List<Option> result = new ArrayList<>();
        lambdaQueryChain(folders).eq(Folder::getSpaceId, spaceId).eq(Folder::getParentId, parent)
                .orderByAsc(Folder::getId).last("LIMIT 200").list()
                .forEach(f -> result.add(new Option(f.getId(), f.getName(), f.getParentId(), "folder")));
        lambdaQueryChain(documents).eq(Document::getSpaceId, spaceId).eq(Document::getFolderId, parent)
                .orderByAsc(Document::getId).last("LIMIT 200").list()
                .forEach(d -> result.add(new Option(d.getId(), d.getName(), d.getFolderId(), "document")));
        return result;
    }

    /** 提交时固化范围内文档，运行中新增文档不会自动扩大范围。 */
    public List<Long> resolve(Long spaceId, Long documentId, Long folderId) {
        if (documentId != null && folderId != null) throw new BusinessException("只能选择一个文件或文件夹");
        if (documentId != null) {
            Document document = documents.selectById(documentId);
            if (document == null || !spaceId.equals(document.getSpaceId())) throw new BusinessException("范围文档不存在或不属于当前空间");
            return List.of(documentId);
        }
        if (folderId == null) return null;
        Set<Long> selected = folderTree(spaceId, folderId);
        var ids = lambdaQueryChain(documents).select(Document::getId).eq(Document::getSpaceId, spaceId)
                .in(Document::getFolderId, selected).orderByAsc(Document::getId).last("LIMIT 31").list()
                .stream().map(Document::getId).toList();
        if (ids.size() > 30) throw new BusinessException("问答范围最多30个文档，请选择更小的文件夹");
        return ids;
    }

    /** 多选文件必须全部属于当前空间，不能静默忽略无权限或已删除的文件。 */
    public List<Long> resolve(Long spaceId, Long documentId, Long folderId, List<Long> documentIds) {
        if (documentIds == null) return resolve(spaceId, documentId, folderId);
        if (documentId != null || folderId != null) throw new BusinessException("文件列表不能与单文件或文件夹范围同时提交");
        List<Long> ids = normalize(documentIds);
        long count = lambdaQueryChain(documents).eq(Document::getSpaceId, spaceId).in(Document::getId, ids).count();
        if (count != ids.size()) throw new BusinessException("范围文档不存在或不属于当前空间");
        return ids;
    }

    /** 统一文件ID顺序，重复点击或调整勾选顺序不改变请求幂等性。 */
    public static List<Long> normalize(List<Long> ids) {
        if (ids.isEmpty() || ids.size() > 30 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException("请选择1到30个有效文档");
        }
        return ids.stream().distinct().sorted().toList();
    }

    /** 只复核快照中的文档，后来新增的文档不影响已提交范围。 */
    public boolean valid(Long spaceId, Long documentId, Long folderId, List<Long> snapshot) {
        if (documentId != null) return resolve(spaceId, documentId, null).equals(snapshot);
        if (folderId == null) {
            if (snapshot.isEmpty() || snapshot.size() > 30) return false;
            return lambdaQueryChain(documents).eq(Document::getSpaceId, spaceId)
                    .in(Document::getId, snapshot).count() == snapshot.size();
        }
        Set<Long> tree = folderTree(spaceId, folderId);
        if (snapshot.isEmpty()) return true;
        return lambdaQueryChain(documents).eq(Document::getSpaceId, spaceId).in(Document::getId, snapshot)
                .in(Document::getFolderId, tree).count() == snapshot.size();
    }

    private Set<Long> folderTree(Long spaceId, Long folderId) {
        requireFolder(spaceId, folderId);
        Set<Long> selected = new LinkedHashSet<>();
        ArrayDeque<Long> pending = new ArrayDeque<>();
        pending.add(folderId);
        while (!pending.isEmpty()) {
            Long id = pending.removeFirst();
            if (!selected.add(id)) throw new BusinessException("目录层级异常");
            if (selected.size() > 200) throw new BusinessException("范围过大，请选择更小的文件夹");
            lambdaQueryChain(folders).eq(Folder::getSpaceId, spaceId).eq(Folder::getParentId, id)
                    .last("LIMIT 201").list().forEach(f -> pending.add(f.getId()));
        }
        return selected;
    }

    private void requireFolder(Long space, Long id) {
        Folder folder = folders.selectById(id);
        if (folder == null || !space.equals(folder.getSpaceId())) throw new BusinessException("范围文件夹不存在或不属于当前空间");
    }
}
