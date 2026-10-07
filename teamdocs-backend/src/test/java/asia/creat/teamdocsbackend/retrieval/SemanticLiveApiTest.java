package asia.creat.teamdocsbackend.retrieval;

import asia.creat.config.EmbeddingProperties;
import asia.creat.config.RerankProperties;
import asia.creat.retrieval.RetrievalHttp;
import asia.creat.retrieval.SiliconFlowEmbeddingClient;
import asia.creat.retrieval.SiliconFlowRerankClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 仅显式授权后访问配置的模型接口，全部输入均为合成资料。 */
@EnabledIfSystemProperty(named = "teamdocs.live-semantic", matches = "true")
class SemanticLiveApiTest {
    private static final String QUERY = "新版本上线后出错，怎样退回之前版本？";
    private static final List<String> DOCUMENTS = List.of(
            "发布失败时先停止新版本流量，再把服务镜像切换到上一个稳定版本，并恢复到已验证的数据库备份。",
            "会议室预约请在每周五下午提交下周申请，管理员按申请时间安排房间。",
            "员工办理门禁需要提交照片和工号，访客登记后领取临时门禁卡。");
    private static Dotenv environment;

    private final RetrievalHttp http = new RetrievalHttp(new ObjectMapper());

    /** 只加载配置，不输出密钥，也不启动完整应用或访问数据库。 */
    @BeforeAll
    static void loadConfiguration() {
        try {
            String directory = Files.exists(Path.of("../.env")) ? ".." : ".";

            environment = Dotenv.configure().directory(directory).ignoreIfMissing().load();
        } catch (RuntimeException e) {
            throw new IllegalStateException("无法读取模型配置，请核对 .env 格式");
        }
    }

    /** 验证真实接口的批量向量、维度，以及合成查询的最邻近资料。 */
    @Test
    void embeddingRetrievesSyntheticRollbackDocument() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setBaseUrl(required("EMBEDDING_BASE_URL"));
        properties.setApiKey(requiredKey("EMBEDDING_API_KEY"));
        properties.setModelName(required("EMBEDDING_MODEL"));
        properties.setDimensions(Integer.parseInt(environment.get("EMBEDDING_DIMENSIONS", "1024")));
        properties.setTimeoutSeconds(Integer.parseInt(environment.get("EMBEDDING_TIMEOUT_SECONDS", "10")));

        SiliconFlowEmbeddingClient client = new SiliconFlowEmbeddingClient(properties, http);
        List<List<Float>> vectors = client.embed(List.of(QUERY, DOCUMENTS.get(0), DOCUMENTS.get(1), DOCUMENTS.get(2)));

        assertEquals(4, vectors.size());
        assertEquals(properties.getDimensions(), vectors.get(0).size());

        double correct = cosine(vectors.get(0), vectors.get(1));

        assertTrue(correct > cosine(vectors.get(0), vectors.get(2)), "回滚资料应比会议室资料更相关");
        assertTrue(correct > cosine(vectors.get(0), vectors.get(3)), "回滚资料应比门禁资料更相关");
    }

    /** 验证真实重排接口返回合法索引，并将相关合成资料排在第一。 */
    @Test
    void rerankerRanksSyntheticRollbackDocumentFirst() {
        RerankProperties properties = new RerankProperties();
        properties.setBaseUrl(required("RERANK_BASE_URL"));
        properties.setApiKey(requiredKey("RERANK_API_KEY"));
        properties.setModelName(required("RERANK_MODEL"));
        properties.setTimeoutSeconds(Integer.parseInt(environment.get("RERANK_TIMEOUT_SECONDS", "10")));

        SiliconFlowRerankClient client = new SiliconFlowRerankClient(properties, http);
        List<Integer> order = client.rerank(QUERY, DOCUMENTS, DOCUMENTS.size());

        assertEquals(3, order.size());
        assertEquals(0, order.get(0));
    }

    /** 读取必填配置，错误只包含变量名，不包含配置值。 */
    private static String required(String name) {
        String value = environment.get(name);

        assertTrue(value != null && !value.isBlank(), "缺少配置：" + name);

        return value;
    }

    /** 拒绝空值和占位 Key，不把它们发送到真实接口。 */
    private static String requiredKey(String name) {
        String value = required(name);

        assertTrue(RetrievalHttp.hasApiKey(value), "未配置有效凭据：" + name);

        return value;
    }

    /** 计算测试向量的余弦相似度，不作为正式评测成绩。 */
    private double cosine(List<Float> left, List<Float> right) {
        double product = 0;
        double leftNorm = 0;
        double rightNorm = 0;

        for (int i = 0; i < left.size(); i++) {
            product += (double) left.get(i) * right.get(i);
            leftNorm += (double) left.get(i) * left.get(i);
            rightNorm += (double) right.get(i) * right.get(i);
        }

        return product / Math.sqrt(leftNorm * rightNorm);
    }
}
