package com.teachloop.sitting.storage;

import com.teachloop.shared.storage.BlobStore;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 兼容对象存储（T386）：阿里云 OSS、MinIO、Cloudflare R2 等都走这同一套代码，只换配置。
 *
 * <p>配置项见 {@code teachloop.storage.s3.*}：{@code endpoint}、{@code region}、{@code bucket}、
 * {@code access-key-id}、{@code access-key-secret}、{@code path-style}、{@code path-prefix}。
 * 阿里云 OSS 只支持 virtual-hosted 风格，所以 {@code path-style} 默认 {@code false}；
 * 本地 MinIO 测试时设 {@code true}。
 *
 * <p>只有 {@code teachloop.storage.backend=s3} 才装配。选 s3 但缺必填项时<b>启动即报错</b>，
 * 报错信息只列缺失的配置项名，不含任何密钥值。运行期 SDK 异常统一翻译成
 * {@link StorageUnavailableException}（503），不把存储故障报成 500。
 */
@Component
@ConditionalOnProperty(name = "teachloop.storage.backend", havingValue = "s3")
public class S3BlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(S3BlobStore.class);

    /** key 只允许本模块生成的形态（与 LocalStorage 一致），避免把用户可控字符串直接拼进对象名。 */
    private static final String KEY_PATTERN = "[A-Za-z0-9._/-]+";

    private final S3Client client;
    private final String bucket;
    private final String pathPrefix;

    public S3BlobStore(
            @Value("${teachloop.storage.s3.endpoint:}") String endpoint,
            @Value("${teachloop.storage.s3.region:cn-hangzhou}") String region,
            @Value("${teachloop.storage.s3.bucket:}") String bucket,
            @Value("${teachloop.storage.s3.access-key-id:}") String accessKeyId,
            @Value("${teachloop.storage.s3.access-key-secret:}") String accessKeySecret,
            @Value("${teachloop.storage.s3.path-style:false}") boolean pathStyle,
            @Value("${teachloop.storage.s3.path-prefix:teachloop/}") String pathPrefix) {
        List<String> missing = new ArrayList<>();
        if (endpoint == null || endpoint.isBlank()) {
            missing.add("teachloop.storage.s3.endpoint");
        }
        if (bucket == null || bucket.isBlank()) {
            missing.add("teachloop.storage.s3.bucket");
        }
        if (accessKeyId == null || accessKeyId.isBlank()) {
            missing.add("teachloop.storage.s3.access-key-id");
        }
        if (accessKeySecret == null || accessKeySecret.isBlank()) {
            missing.add("teachloop.storage.s3.access-key-secret");
        }
        if (!missing.isEmpty()) {
            // 只报配置项名，绝不带值：缺 secret 时不能把「已填了一半」的密钥打进日志。
            throw new IllegalStateException(
                    "已选择 S3 存储但缺少配置项：" + String.join("、", missing)
                            + "；开发环境请用 teachloop.storage.backend=local");
        }

        this.bucket = bucket;
        this.pathPrefix = pathPrefix == null ? "" : pathPrefix.trim();
        this.client = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, accessKeySecret)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .build())
                .endpointOverride(URI.create(endpoint))
                .build();
        log.info("附件对象存储：S3 兼容端点 {}，bucket {}，path-style {}，前缀 {}",
                endpoint, bucket, pathStyle, pathPrefix);
    }

    @Override
    public String store(String key, byte[] content, String contentType) {
        String objectKey = objectKey(key);
        String type = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        try {
            client.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(type)
                    .build(), RequestBody.fromBytes(content));
            return key;
        } catch (SdkException ex) {
            throw unavailable("写入", ex);
        }
    }

    @Override
    public Optional<byte[]> load(String key) {
        String objectKey = objectKey(key);
        try {
            byte[] bytes = client.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .build())
                    .asByteArray();
            return Optional.of(bytes);
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw unavailable("读取", ex);
        } catch (SdkException ex) {
            throw unavailable("读取", ex);
        }
    }

    @Override
    public boolean exists(String key) {
        String objectKey = objectKey(key);
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
            return true;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return false;
            }
            throw unavailable("检查", ex);
        } catch (SdkException ex) {
            throw unavailable("检查", ex);
        }
    }

    /** 拼上 path-prefix；key 本身仍按 LocalStorage 的规则校验。 */
    private String objectKey(String key) {
        if (key == null || key.isBlank() || key.contains("..") || key.startsWith("/")
                || key.contains("\\") || !key.matches(KEY_PATTERN)) {
            throw new IllegalArgumentException("非法的附件 key");
        }
        if (pathPrefix.isEmpty()) {
            return key;
        }
        String prefix = pathPrefix.startsWith("/") ? pathPrefix.substring(1) : pathPrefix;
        return prefix.endsWith("/") ? prefix + key : prefix + "/" + key;
    }

    /**
     * 把 SDK 异常翻译成 503，且只保留 HTTP 状态码 / 异常类名：SDK 的原始消息可能带请求地址，
     * 一律不拼进对外消息；密钥从未进入这些消息。
     */
    private StorageUnavailableException unavailable(String action, SdkException ex) {
        String reason = ex instanceof S3Exception s3 && s3.statusCode() > 0
                ? "HTTP " + s3.statusCode()
                : ex.getClass().getSimpleName();
        log.warn("S3 {}失败：{}（bucket={}）", action, reason, bucket, ex);
        return new StorageUnavailableException(
                "附件存储（S3）暂不可用，请稍后重试或联系管理员", ex);
    }
}
