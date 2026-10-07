package asia.creat.teamdocsbackend.common;

import asia.creat.agent.AgentData;
import asia.creat.entity.Comment;
import asia.creat.entity.Document;
import asia.creat.entity.DocumentContent;
import asia.creat.entity.Folder;
import asia.creat.entity.OperationLogRecord;
import asia.creat.service.impl.ElasticsearchChunkIndex;
import asia.creat.vo.ChunkReadVO;
import asia.creat.vo.DocumentParseStatusVO;
import asia.creat.vo.DocumentPreviewVO;
import asia.creat.vo.UserProfileVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuilderCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    static Stream<Class<?>> types() {
        return Stream.of(
                Document.class, DocumentContent.class, OperationLogRecord.class,
                Comment.class, Folder.class,
                UserProfileVO.class, DocumentParseStatusVO.class, DocumentPreviewVO.class, ChunkReadVO.class,
                AgentData.Run.class, AgentData.Message.class, AgentData.Trace.class, AgentData.ModelCall.class,
                ElasticsearchChunkIndex.IndexedChunk.class
        );
    }

    @ParameterizedTest(name = "{0}: public no-arg constructor and defaults")
    @MethodSource("types")
    void builderKeepsThePublicNoArgConstructorAndDefaultValues(Class<?> type) throws Exception {
        Object empty = type.getConstructor().newInstance();
        Object builder = type.getMethod("builder").invoke(null);
        Object built = builder.getClass().getMethod("build").invoke(builder);

        assertEquals(empty, built);
        assertEquals(json.valueToTree(empty), json.valueToTree(built));
    }

    @ParameterizedTest(name = "{0}: setters, builder and JSON agree")
    @MethodSource("types")
    void builderPreservesEveryFieldAndJacksonRoundTrips(Class<?> type) throws Exception {
        Object mutable = type.getConstructor().newInstance();
        Object builder = type.getMethod("builder").invoke(null);

        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;

            Object value = sample(field.getType(), field.getName());
            String name = field.getName();
            String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);

            type.getMethod(setter, field.getType()).invoke(mutable, value);
            builder.getClass().getMethod(name, field.getType()).invoke(builder, value);
        }

        Object built = builder.getClass().getMethod("build").invoke(builder);

        assertEquals(mutable, built);
        assertEquals(json.valueToTree(mutable), json.valueToTree(built));
        assertEquals(built, json.readValue(json.writeValueAsString(built), type));
    }

    private static Object sample(Class<?> type, String name) {
        if (type == String.class) return "sample-" + name;

        if (type == Long.class || type == long.class) return 42L;

        if (type == Integer.class || type == int.class) return 1;

        if (type == Boolean.class || type == boolean.class) return true;

        if (type == LocalDateTime.class) return LocalDateTime.of(2026, 10, 4, 12, 30);

        if (type == List.class) return List.of();

        if (type.isEnum()) return type.getEnumConstants()[0];

        throw new AssertionError("Missing sample value for " + type.getName());
    }
}
