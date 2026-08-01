package asia.creat;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableAsync
public class TeamdocsBackendApplication {
    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().
                ignoreIfMissing().load();

        dotenv.entries().forEach(entry ->
                System.setProperty(entry.getKey(), entry.getValue())
        );

        SpringApplication.run(TeamdocsBackendApplication.class, args);
    }
}
