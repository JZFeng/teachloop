package com.teachloop.sitting.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T386：{@link S3BlobStore} 的启动校验，不需要网络或凭据。
 *
 * <p>选 {@code backend=s3} 但缺必填项时启动即失败；报错只列缺失的配置项名，
 * 不得出现 AccessKey 或 secret 的值。
 *
 * <p>与集成测试同一个 {@code @Tag("oss")}：默认 {@code mvn test} 不跑，随 {@code make test-oss} 执行。
 */
@Tag("oss")
class S3BlobStoreConfigTest {

    @Test
    void missingRequiredItemsListPropertyNamesOnly() {
        assertThatThrownBy(() -> new S3BlobStore("", "cn-hangzhou", "", "", "",
                false, "teachloop/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("teachloop.storage.s3.endpoint")
                .hasMessageContaining("teachloop.storage.s3.bucket")
                .hasMessageContaining("teachloop.storage.s3.access-key-id")
                .hasMessageContaining("teachloop.storage.s3.access-key-secret");
    }

    @Test
    void missingBucketNeverEchoesConfiguredSecret() {
        String secret = "s3-secret-must-not-appear-in-errors";
        assertThatThrownBy(() -> new S3BlobStore(
                "http://127.0.0.1:9000", "us-east-1", "", "test-key-id", secret,
                true, "teachloop/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("teachloop.storage.s3.bucket")
                .hasMessageNotContaining(secret);
    }
}
