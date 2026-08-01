package asia.creat.service;

import asia.creat.common.BucketType;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface FileStorageService {
    void upload(MultipartFile file, BucketType bucket, String objectKey);
    void delete(BucketType bucket, String objectKey);
    String getAccessUrl(BucketType bucket, String objectKey, Map<String, String> queryParams);
}
