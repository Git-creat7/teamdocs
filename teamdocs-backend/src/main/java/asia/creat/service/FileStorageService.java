package asia.creat.service;

import asia.creat.common.BucketType;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;

public interface FileStorageService {
    void upload(MultipartFile file, BucketType bucket, String objectKey);

    void delete(BucketType bucket, String objectKey);

    String getAccessUrl(BucketType bucket, String objectKey, Map<String, String> queryParams);

    // 解析任务按 objectKey 读原文件，调用方负责关闭流
    InputStream open(BucketType bucket, String objectKey);
}
