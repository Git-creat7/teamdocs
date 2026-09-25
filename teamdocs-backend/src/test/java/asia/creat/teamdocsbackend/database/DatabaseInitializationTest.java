package asia.creat.teamdocsbackend.database;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DatabaseInitializationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test
    void initializesEmptyDatabaseUsingDeploymentScripts() throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            // 与 docker-compose.yaml 中的初始化顺序一致。
            for (String script : List.of("initUser.sql", "initSpace.sql", "initDocument.sql",
                    "initComment.sql", "initOperationLog.sql", "fulltext_index.sql", "document_content.sql")) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource("../sql/" + script), StandardCharsets.UTF_8));
            }
            Set<String> tables = new HashSet<>();
            try (ResultSet rows = statement.executeQuery("SHOW TABLES")) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            assertEquals(Set.of("user", "space", "space_member", "folder", "document", "tag",
                    "document_tag", "comment", "operation_log", "document_content"), tables);

            statement.execute("INSERT INTO document (space_id, folder_id, name, file_path, upload_by) VALUES (1, 0, '上线检查', 'space/1/checks.txt', 1)");
            try (ResultSet row = statement.executeQuery("SELECT parse_status, chunk_count, parse_version, parse_started_at FROM document")) {
                assertTrue(row.next());
                assertEquals("PENDING", row.getString("parse_status"));
                assertEquals(0, row.getInt("chunk_count"));
                assertEquals(0, row.getInt("parse_version"));
                assertNull(row.getTimestamp("parse_started_at"));
            }
            statement.execute("INSERT INTO document_content (document_id, space_id, chunk_index, content) VALUES (1, 1, 0, '初始化正文')");
            assertThrows(java.sql.SQLException.class, () -> statement.execute(
                    "INSERT INTO document_content (document_id, space_id, chunk_index, content) VALUES (1, 1, 0, '重复分块')"));
            try (ResultSet row = statement.executeQuery("SELECT COUNT(*) FROM document WHERE MATCH(name) AGAINST('上线' IN BOOLEAN MODE)")) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1));
            }
        }
    }
}
