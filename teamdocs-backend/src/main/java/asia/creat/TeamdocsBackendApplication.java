package asia.creat;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Configuration;


//暂时移除ES
@SpringBootApplication(exclude = ElasticsearchRepositoriesAutoConfiguration.class)
@Configuration
public class TeamdocsBackendApplication {
    public static void main(String[] args) {
        System.out.println("Working dir: " + System.getProperty("user.dir"));

        // 加载.env
        Dotenv dotenv = Dotenv.configure().
                ignoreIfMissing().load();

        dotenv.entries().forEach(entry ->
                System.setProperty(entry.getKey(), entry.getValue())
        );
        System.out.println("DB_PASSWORD loaded: " + (System.getProperty("DB_PASSWORD") != null));
        SpringApplication.run(TeamdocsBackendApplication.class, args);
    }
}
