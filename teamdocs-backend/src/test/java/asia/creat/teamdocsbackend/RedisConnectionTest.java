package asia.creat.teamdocsbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.Duration;

@ActiveProfiles("test")
@SpringBootTest(classes = {
        RedisAutoConfiguration.class,
})
public class RedisConnectionTest {


    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testConnection(){
        stringRedisTemplate.opsForValue().set("teamdocs:test:connection", "ok", Duration.ofSeconds(60L));
        String json = stringRedisTemplate.opsForValue().get("teamdocs:test:connection");
        System.out.println(json);
    }
}
