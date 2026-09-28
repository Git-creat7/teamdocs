package asia.creat.teamdocsbackend.retrieval;

import asia.creat.config.ElasticsearchConfiguration;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.service.ChunkIndex;
import asia.creat.service.impl.ElasticsearchChunkIndex;
import asia.creat.service.impl.NoopChunkIndex;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ElasticsearchConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ElasticsearchConfiguration.class, NoopChunkIndex.class, ElasticsearchChunkIndex.class)
            .withBean(DocumentContentMapper.class, () -> mock(DocumentContentMapper.class));

    @Test
    void disabledByDefaultAndOfflineEnabledClientDoesNotContactServerAtStartup() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ChunkIndex.class);
            assertThat(context.getBean(ChunkIndex.class)).isInstanceOf(NoopChunkIndex.class);
        });
        runner.withPropertyValues("teamdocs.elasticsearch.enabled=true", "teamdocs.elasticsearch.url=http://127.0.0.1:1")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ChunkIndex.class);
                    assertThat(context.getBean(ChunkIndex.class)).isInstanceOf(ElasticsearchChunkIndex.class);
                });
    }

    @Test
    void maintenanceProcessExitsCleanlyThroughRealSpringBootStartup() {
        SpringApplication app = new SpringApplication(MaintenanceApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--teamdocs.elasticsearch.rebuild=true",
                "--spring.config.location=optional:classpath:/maintenance-test.properties")) {
            assertThat(context.isActive()).isFalse();
        }
    }

    @Configuration
    @Import(ElasticsearchConfiguration.class)
    static class MaintenanceApplication {
        @Bean ChunkIndex index() {
            ChunkIndex index = mock(ChunkIndex.class);
            when(index.rebuild()).thenReturn(5);
            return index;
        }
    }

    @Test
    void maintenanceEntryClosesOnlyAfterSuccessfulRebuildAndPropagatesFailures() throws Exception {
        ChunkIndex index = mock(ChunkIndex.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        var command = new ElasticsearchConfiguration().rebuildChunkIndex(index, context);
        when(index.rebuild()).thenReturn(5);
        command.run(new DefaultApplicationArguments());
        verify(context).close();
        reset(context);
        when(index.rebuild()).thenThrow(new IllegalStateException("failed"));
        assertThrows(IllegalStateException.class, () -> command.run(new DefaultApplicationArguments()));
        verify(context, never()).close();
    }
}
