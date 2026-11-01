package asia.creat.retrieval;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
public class RetrievalHttp {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ObjectMapper mapper;
    private final OkHttpClient client;

    /**
     * 创建不自动重试的 JSON 客户端。
     * @param mapper 项目 JSON 配置
     */
    public RetrievalHttp(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 在剩余执行时间内发送有界请求。
     * @param baseUrl 服务基础地址
     * @param path 接口路径
     * @param token Bearer 凭据，可为空
     * @param payload 请求内容
     * @param timeoutSeconds 请求超时秒数
     * @return 已解析的 JSON 对象
     */
    public JsonNode post(String baseUrl, String path, String token, Object payload, int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new RetrievalException("检索服务连接配置无效");
        }
        return post(baseUrl, path, token, payload, 2000L, timeoutSeconds * 1000L, 5000L);
    }

    /**
     * 在剩余执行时间内发送有界请求，支持细粒度超时控制。
     * @param baseUrl 服务基础地址
     * @param path 接口路径
     * @param token Bearer 凭据，可为空
     * @param payload 请求内容
     * @param connectTimeoutMs 连接超时毫秒
     * @param readTimeoutMs 读取超时毫秒
     * @param writeTimeoutMs 写入超时毫秒
     * @return 已解析的 JSON 对象
     */
    public JsonNode post(String baseUrl, String path, String token, Object payload,
                         long connectTimeoutMs, long readTimeoutMs, long writeTimeoutMs) {
        if (baseUrl == null || baseUrl.isBlank() || path == null || !path.startsWith("/")
                || connectTimeoutMs < 1 || readTimeoutMs < 1 || writeTimeoutMs < 1) {
            throw new RetrievalException("检索服务连接配置无效");
        }

        Request request;

        try {
            byte[] body = mapper.writeValueAsBytes(payload);

            if (body.length > MAX_BYTES) {
                throw new RetrievalException("检索请求超过大小上限");
            }

            Request.Builder builder = new Request.Builder()
                    .url(baseUrl.replaceAll("/+$", "") + path)
                    .post(RequestBody.create(body, JSON))
                    .header("Accept", "application/json");

            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token.trim());
            }

            request = builder.build();
        } catch (IOException | IllegalArgumentException e) {
            throw new RetrievalException("检索请求配置或编码失败");
        }

        // 检查取消与剩余预算；budgetMillis <= 0 时必须立即失败，绝不向 OkHttp 传入 0（无超时）。
        long budgetMillis = RetrievalContext.timeoutMillis(readTimeoutMs);
        if (budgetMillis <= 0) {
            throw new IllegalStateException("检索运行已超时");
        }
        long effectiveConnectMs = Math.min(connectTimeoutMs, budgetMillis);
        long effectiveWriteMs = Math.min(writeTimeoutMs, budgetMillis);
        if (effectiveConnectMs <= 0 || effectiveWriteMs <= 0) {
            throw new IllegalStateException("检索运行已超时");
        }
        OkHttpClient requestClient = client.newBuilder()
                .connectTimeout(effectiveConnectMs, TimeUnit.MILLISECONDS)
                .readTimeout(budgetMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(effectiveWriteMs, TimeUnit.MILLISECONDS)
                .callTimeout(budgetMillis, TimeUnit.MILLISECONDS)
                .build();
        Call call = requestClient.newCall(request);

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new RetrievalException("检索服务 HTTP " + response.code());
            }

            if (response.body() == null || response.body().contentLength() > MAX_BYTES) {
                throw new RetrievalException("检索响应为空或超过大小上限");
            }

            byte[] bytes = response.body().byteStream().readNBytes(MAX_BYTES + 1);

            if (bytes.length > MAX_BYTES) {
                throw new RetrievalException("检索响应超过大小上限");
            }

            JsonNode result = mapper.readTree(bytes);

            if (result == null || !result.isObject()) {
                throw new RetrievalException("检索响应不是 JSON 对象");
            }

            RetrievalContext.check();

            return result;
        } catch (IOException e) {
            RetrievalContext.check();
            throw new RetrievalException("检索服务请求失败或超时");
        }
    }

    /**
     * 排除空凭据和常见占位值。
     * @param value 配置中的 API Key
     * @return 是否具备非占位凭据
     */
    public static boolean hasApiKey(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        String key = value.trim().toLowerCase(Locale.ROOT);

        if (key.chars().anyMatch(character -> character <= 32 || character >= 127)) {
            return false;
        }

        return !key.startsWith("your_") && !key.startsWith("your-")
                && !key.equals("replace-me") && !key.equals("replace_me")
                && !key.equals("placeholder") && !key.equals("changeme") && !key.equals("null")
                && !key.contains("占位");
    }
}
