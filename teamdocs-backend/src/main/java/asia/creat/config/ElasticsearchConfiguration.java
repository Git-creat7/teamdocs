package asia.creat.config;

import asia.creat.service.ChunkIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ElasticsearchProperties.class)
@Slf4j
public class ElasticsearchConfiguration {
    /** 仅供运维一次性进程使用，不暴露全局重建 HTTP 接口。失败时启动失败并返回非零退出码。 */
    @Bean
    @ConditionalOnProperty(prefix = "teamdocs.elasticsearch", name = "rebuild", havingValue = "true")
    public ApplicationRunner rebuildChunkIndex(ChunkIndex index, ConfigurableApplicationContext context) {
        return args -> {
            int count = index.rebuild();

            log.info("Elasticsearch 全量重建完成，共 {} 个当前分块", count);
            context.close();
        };
    }
}
