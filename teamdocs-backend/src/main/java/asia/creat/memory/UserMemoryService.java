package asia.creat.memory;

import asia.creat.agent.AgentData.Run;
import asia.creat.common.exception.BusinessException;
import asia.creat.agent.AgentRepository;
import asia.creat.mapper.UserMemoryMapper;
import asia.creat.mapper.UserMemoryJobMapper;
import asia.creat.mapper.UserMapper;
import asia.creat.entity.User;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import asia.creat.memory.UserMemoryData.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaQueryChain;

@Service
@RequiredArgsConstructor
public class UserMemoryService extends ServiceImpl<UserMemoryMapper, Row> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final UserMemoryJobMapper jobs;
    private final UserMapper users;
    private final AgentRepository agents;

    /**
     * 获取当前用户的记忆设置和条目。
     * @param userId 用户ID
     * @return 未开启时返回默认设置
     */
    public View view(long userId) {
        if (!lambdaQueryChain(users).eq(User::getId, userId).eq(User::getStatus, 1).exists()) throw new BusinessException("用户不可用");
        Row row = getById(userId);

        return row == null ? new View(false, 0, List.of()) : view(row);
    }

    /**
     * 读取用于问答的记忆，关闭时不返回内容。
     * @param userId 用户ID
     * @return 按更新时间倒序排列的条目
     */
    public List<Item> contextItems(long userId) {
        View view = view(userId);
        return view.enabled() ? UserMemoryPolicy.newestFirst(view.items()) : List.of();
    }

    /**
     * 更新记忆开关并使旧任务失效。
     * @param userId 用户ID
     * @param request 开关和当前版本
     * @return 更新后的设置
     */
    @Transactional
    public View settings(long userId, Settings request) {
        Row row = editable(userId, request.version());
        row.setEnabled(request.enabled());

        return userChange(row, items(row));
    }

    /**
     * 修改指定条目，保留其他记忆。
     * @param userId 用户ID
     * @param key 条目标识
     * @param request 新内容和当前版本
     * @return 更新后的设置
     */
    @Transactional
    public View edit(long userId, String key, Edit request) {
        String value = UserMemoryPolicy.checkedValue(key, request.content());
        Row row = editable(userId, request.version());
        List<Item> items = new ArrayList<>(items(row));
        int index = index(items, key);
        items.set(index, new Item(key, value, null, Instant.now().toString()));

        return userChange(row, items);
    }

    /**
     * 删除指定条目并作废旧任务。
     * @param userId 用户ID
     * @param key 条目标识
     * @param version 当前版本
     * @return 更新后的设置
     */
    @Transactional
    public View delete(long userId, String key, long version) {
        Row row = editable(userId, version);
        List<Item> items = new ArrayList<>(items(row));
        items.remove(index(items, key));

        return userChange(row, items);
    }

    /**
     * 清空用户记忆，不修改开关。
     * @param userId 用户ID
     * @param version 当前版本
     * @return 更新后的设置
     */
    @Transactional
    public View clear(long userId, long version) {
        return userChange(editable(userId, version), List.of());
    }

    /**
     * 与新运行同事务登记任务，仅成功的运行可被处理。
     * @param run 新建运行
     */
    public void register(Run run) {
        Row row = getById(run.getUserId());
        if (row == null || !row.isEnabled()) return;

        Job job = new Job();
        job.setRunId(run.getId());
        job.setUserId(run.getUserId());
        job.setMemoryVersion(row.getVersion());
        job.setStatus("PENDING");
        jobs.insert(job);
    }

    /**
     * 保存抽取结果，拒绝权限、版本或租约已经失效的任务。
     * @param job 已领取任务
     * @param candidates 模型候选条目
     */
    @Transactional
    public void apply(Job job, List<Candidate> candidates) {
        // 与问答创建、记忆编辑共用用户行锁，避免互相覆盖。
        if (agents.lockUser(job.getUserId()) == null) return;
        Row row = getById(job.getUserId());
        Job current = jobs.current(job.getRunId(), job.getClaimToken(), System.currentTimeMillis());
        if (row == null || current == null || !row.isEnabled() || row.getVersion() != job.getMemoryVersion()) return;

        List<Item> result = UserMemoryPolicy.merge(items(row), candidates, current.getQuestion(), current.getRunId());
        // 先以租约令牌原子认领提交权；与内容写入同事务，旧持有者不能覆盖新结果。
        if (!jobs.complete(job.getRunId(), job.getClaimToken(), System.currentTimeMillis())) return;
        row.setItemsJson(write(result));
        updateById(row);
    }

    private Row editable(long userId, long version) {
        if (agents.lockUser(userId) == null) throw new BusinessException("用户不可用");
        // 用户行锁串行化首次创建和后续更新，不需要 INSERT IGNORE。
        Row row = getById(userId);
        if (row == null) {
            row = new Row();
            row.setUserId(userId);
            row.setItemsJson("[]");
            save(row);
        }

        if (row.getVersion() != version) throw new BusinessException("记忆设置已变化，请刷新后重试");
        return row;
    }

    private View userChange(Row row, List<Item> items) {
        row.setVersion(row.getVersion() + 1);
        row.setItemsJson(write(items));
        updateById(row);
        jobs.discard(row.getUserId());
        return view(row);
    }

    private int index(List<Item> items, String key) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).key().equals(key)) return i;
        throw new BusinessException("记忆不存在，请刷新后重试");
    }

    private View view(Row row) {
        return new View(row.isEnabled(), row.getVersion(), items(row));
    }

    private List<Item> items(Row row) {
        try {
            return JSON.readValue(row.getItemsJson(), new TypeReference<List<Item>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("用户记忆数据无效");
        }
    }

    private String write(List<Item> items) {
        try {
            return JSON.writeValueAsString(items);
        } catch (Exception error) {
            throw new IllegalStateException("用户记忆序列化失败");
        }
    }
}
