package com.teachloop.sitting.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * T386：{@link S3BlobStore} 对真实 S3 兼容存储（MinIO 或阿里云 OSS）的集成测试。
 *
 * <p>默认 {@code mvn test} 跳过（{@code @Tag("oss")} + surefire {@code excludedGroups}）。
 * 手工运行：{@code make oss-dev && make test-oss}，或导出阿里云 {@code STORAGE_S3_*} 后
 * {@code make test-oss}。未设置 {@code STORAGE_S3_ENDPOINT} 时用 assumption 跳过并打印原因，
 * 不会静默通过。
 */
@Tag("oss")
class S3BlobStoreTest {

    private static final String ENDPOINT = getenv("STORAGE_S3_ENDPOINT", "");
    private static final String REGION = getenv("STORAGE_S3_REGION", "us-east-1");
    private static final String BUCKET = getenv("STORAGE_S3_BUCKET", "teachloop-minio");
    private static final String ACCESS_KEY_ID = getenv("STORAGE_S3_ACCESS_KEY_ID", "teachloop");
    private static final String ACCESS_KEY_SECRET =
            getenv("STORAGE_S3_ACCESS_KEY_SECRET", "teachloop-minio-secret");
    private static final boolean PATH_STYLE =
            Boolean.parseBoolean(getenv("STORAGE_S3_PATH_STYLE", "true"));
    private static final String PATH_PREFIX = getenv("STORAGE_S3_PATH_PREFIX", "teachloop/");

    private static final String KEY = "submissions/t386/" + UUID.randomUUID() + ".bin";
    private static final byte[] CONTENT = allByteValues();

    @BeforeAll
    static void requireEndpointAndBucket() {
        Assumptions.assumeTrue(!ENDPOINT.isBlank(),
                "跳过 S3 集成测试：未设置 STORAGE_S3_ENDPOINT（先 make oss-dev 起 MinIO，或导出阿里云 S3 变量）");
        ensureBucketExists();
    }

    @Test
    void storesThenLoadsIdenticalBytes() {
        S3BlobStore store = newStore();
        assertThat(store.store(KEY, CONTENT, "application/octet-stream")).isEqualTo(KEY);
        assertThat(store.load(KEY)).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(CONTENT));
    }

    @Test
    void existsIsTrueForStoredKey() {
        S3BlobStore store = newStore();
        store.store(KEY, CONTENT, "application/octet-stream");
        assertThat(store.exists(KEY)).isTrue();
    }

    @Test
    void missingKeyLoadsEmptyAndDoesNotExist() {
        S3BlobStore store = newStore();
        String missing = "submissions/t386/missing-" + UUID.randomUUID() + ".bin";
        assertThat(store.load(missing)).isEmpty();
        assertThat(store.exists(missing)).isFalse();
    }

    private static S3BlobStore newStore() {
        return new S3BlobStore(ENDPOINT, REGION, BUCKET, ACCESS_KEY_ID, ACCESS_KEY_SECRET,
                PATH_STYLE, PATH_PREFIX);
    }

    /** MinIO 不会自动建桶；测试自己建，避免要求人工初始化。 */
    private static void ensureBucketExists() {
        try (S3Client client = newClient()) {
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build());
            } catch (S3Exception ex) {
                if (ex.statusCode() != 404) {
                    throw ex;
                }
                client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            }
        }
    }

    private static S3Client newClient() {
        return S3Client.builder()
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY_ID, ACCESS_KEY_SECRET)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(PATH_STYLE)
                        .build())
                .endpointOverride(URI.create(ENDPOINT))
                .build();
    }

    /** 0–255 全字节序列：验证二进制不会被当成文本改写。 */
    private static byte[] allByteValues() {
        byte[] bytes = new byte[512];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 256);
        }
        return bytes;
    }

    private static String getenv(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
