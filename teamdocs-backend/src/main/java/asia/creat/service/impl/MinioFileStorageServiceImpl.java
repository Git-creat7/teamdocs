package asia.creat.service.impl;

import asia.creat.common.BucketType;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.MinioProperties;
import asia.creat.service.FileStorageService;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/*
 * Minio存储相关，可更换
 * */
@Service
@Slf4j
public class MinioFileStorageServiceImpl implements FileStorageService {
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public MinioFileStorageServiceImpl(MinioClient minioClient,MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    @Override
    public void upload(MultipartFile file, BucketType bucket, String objectKey) {
        try{
             minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(resolveBucketName(bucket))
                    .object(objectKey)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build()
            );
        }catch(Exception e){
            log.error("文件上传失败: objectKey={}, error={}", objectKey, e.getMessage());
            throw new BusinessException("文件上传失败", e);
        }
    }

    @Override
    public void delete(BucketType bucket, String objectKey) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                    .bucket(resolveBucketName(bucket))
                    .object(objectKey)
                    .build()
            );
        } catch (Exception e) {
            log.error("文件删除失败: objectKey={}, error={}", objectKey, e.getMessage());
            throw new BusinessException("文件删除失败", e);
        }
    }

    @Override
    public String getAccessUrl(BucketType bucket, String objectKey, Map<String, String> queryParams) {
        String url = null;
        if(bucket == BucketType.PUBLIC) {
            // 公共桶拼接URL
            url = String.format("%s/%s/%s",
                    minioProperties.getEndpoint(),
                    resolveBucketName(bucket),
                    objectKey);
            log.debug("生成公共访问URL: {}", url);

        }else{
            // 私有桶生成预签名URL
            try {
                var builder = GetPresignedObjectUrlArgs.builder()
                        .method(io.minio.http.Method.GET)
                        .bucket(resolveBucketName(bucket))
                        .object(objectKey)
                        .expiry(1, java.util.concurrent.TimeUnit.HOURS);

                if(queryParams != null && !queryParams.isEmpty()) {
                    builder.extraQueryParams(queryParams);
                }

                url  = minioClient.getPresignedObjectUrl(builder.build());
                log.debug("生成私有访问URL: {}", url);

            } catch (Exception e) {
                log.error("生成访问URL失败: objectKey={}, error={}", objectKey, e.getMessage());
                throw new BusinessException("生成访问URL失败", e);
            }
        }
        return url;
    }

    /*
    * 根据BucketType枚举值返回对应的桶名称。
    * */
    private String resolveBucketName(BucketType bucket) {
        log.debug("解析桶名称: bucketType={}", bucket);
        return bucket == BucketType.PUBLIC
            ? minioProperties.getBucketPublic()
            : minioProperties.getBucketPrivate();
    }
}