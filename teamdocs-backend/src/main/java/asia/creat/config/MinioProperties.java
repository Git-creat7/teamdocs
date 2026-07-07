package asia.creat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "minio")
//告诉 Spring 把 minio.xx 配置项映射到这个类的字段上。
// accessKey 字段对应 minio.access-key（中划线 →驼峰自动转换）
public class MinioProperties {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucketPublic;
    private String bucketPrivate;
}
