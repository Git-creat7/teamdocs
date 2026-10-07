package asia.creat.model;

import asia.creat.agent.AgentBudget;
import asia.creat.agent.AgentData.Run;
import asia.creat.agent.AgentFailure;
import asia.creat.agent.model.OpenAiReasoningChatModel;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.AgentProperties;
import asia.creat.mapper.UserMapper;
import asia.creat.mapper.UserModelMapper;
import asia.creat.model.UserModelData.*;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 私人模型与系统模型明确分流，不在失败后切换供应商。 */
@Service
@RequiredArgsConstructor
public class UserModelService extends ServiceImpl<UserModelMapper, Config> {
    private final ModelSecretCipher cipher;
    private final AgentProperties system;
    private final UserMapper users;
    private final ObjectMapper json;

    /**
     * 读取本人的脱敏配置，不返回密文或 API Key。
     * @param userId 用户ID
     * @return 配置和主密钥可用状态
     */
    public View view(long userId) {
        Config row = getById(userId);
        return row == null ? new View(false, 0, "", "", false, cipher.ready())
                : new View(row.isEnabled(), row.getVersion(), row.getBaseUrl(), row.getModelName(),
                        row.getKeyCiphertext() != null, cipher.ready());
    }

    /**
     * 保存私人配置，用户行锁和版本检查避免多端覆盖。
     * @param userId 用户ID
     * @param request 用户填写的配置
     * @return 保存后的脱敏配置
     */
    @Transactional
    public View save(long userId, Edit request) {
        if (users.lockActiveUser(userId) == null) throw new BusinessException("用户不可用");
        Config row = getById(userId);
        long version = row == null ? 0 : row.getVersion();
        if (version != request.version()) throw new BusinessException("模型配置已变化，请刷新后重试");
        Credentials credentials = draft(userId, request);
        if (row == null) {
            row = new Config();
            row.setUserId(userId);
        }

        row.setBaseUrl(credentials.baseUrl());
        row.setModelName(credentials.modelName());
        row.setKeyCiphertext(cipher.encrypt(userId, "config", credentials.apiKey()));
        row.setEnabled(request.enabled());
        row.setVersion(version + 1);
        saveOrUpdate(row);

        return view(userId);
    }

    /** 即使主密钥不可用也能停用私人配置，不再向该供应商发起新问答。 */
    @Transactional
    public View disable(long userId, long version) {
        if (users.lockActiveUser(userId) == null) throw new BusinessException("用户不可用");
        Config row = getById(userId);
        if (row == null) return view(userId);
        if (row.getVersion() != version) throw new BusinessException("模型配置已变化，请刷新后重试");
        lambdaUpdate().eq(Config::getUserId, userId).set(Config::isEnabled, false)
                .setIncrBy(Config::getVersion, 1).update();
        return view(userId);
    }

    /** 校验草稿；密钥留空时复用本人已保存的密钥。 */
    public Credentials draft(long userId, Edit edit) {
        String url = PublicModelEndpoint.validate(edit.baseUrl());
        String model = edit.modelName().strip();
        if (model.isBlank() || model.length() > 100 || model.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException("模型名称无效");
        }
        String key = edit.apiKey();
        if (key == null || key.isBlank()) {
            Config stored = getById(userId);
            if (stored == null || stored.getKeyCiphertext() == null) throw new BusinessException("请填写 API Key");
            key = cipher.decrypt(userId, "config", stored.getKeyCiphertext());
        }
        if (key.length() > 4096 || key.chars().anyMatch(c -> c <= 32 || c >= 127)) throw new BusinessException("API Key 格式无效");
        return new Credentials(url, model, key);
    }

    /** 与新运行一起保存加密快照，随后修改设置不影响这次请求。 */
    public void snapshot(Run run) {
        Config row = getById(run.getUserId());
        if (row == null || !row.isEnabled()) {
            AgentBudget.requireConfigured(system);
            return;
        }
        requirePersonalAllowed();
        Credentials value = new Credentials(PublicModelEndpoint.validate(row.getBaseUrl()), row.getModelName(),
                cipher.decrypt(run.getUserId(), "config", row.getKeyCiphertext()));
        try {
            run.setModelConfigCiphertext(cipher.encrypt(run.getUserId(), "run", json.writeValueAsString(value)));
            run.setModelName(value.modelName());
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException("模型快照保存失败");
        }
    }

    public boolean hasPersonal(long userId) {
        Config row = getById(userId);
        return row != null && row.isEnabled();
    }

    /** 解密来源运行的配置，不读取用户后来修改的新配置。 */
    public Credentials credentials(Run run) {
        requirePersonalAllowed();
        try {
            Credentials value = json.readValue(cipher.decrypt(run.getUserId(), "run", run.getModelConfigCiphertext()), Credentials.class);
            PublicModelEndpoint.validate(value.baseUrl());
            return value;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException("模型快照无效，请重新发起问答");
        }
    }

    /** 私人模型强制使用公网地址策略，记忆及检测采用有界非流式请求。 */
    public OpenAiReasoningChatModel model(Credentials value, boolean extraction) {
        requirePersonalAllowed();
        AgentProperties config = new AgentProperties();
        config.setBaseUrl(PublicModelEndpoint.validate(value.baseUrl()));
        config.setModelName(value.modelName());
        config.setApiKey(value.apiKey());
        config.setPublicEndpointOnly(true);
        config.setProxyUrl(system.getProxyUrl());
        config.setStreaming(!extraction && system.isStreaming());
        config.setTimeoutSeconds(extraction ? 12 : Math.min(60, Math.max(1, system.getTimeoutSeconds())));
        config.setMaxOutputTokens(extraction ? 512 : system.getMaxOutputTokens());
        return new OpenAiReasoningChatModel(config, json);
    }

    private void requirePersonalAllowed() {
        if (Boolean.FALSE.equals(system.getEnabled()) || Boolean.FALSE.equals(system.getAllowDocumentEgress())) {
            throw new AgentFailure("AI_NOT_AUTHORIZED");
        }
    }
}
