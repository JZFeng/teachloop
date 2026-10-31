package com.teachloop.vocab.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.teachloop.shared.error.ApiException;
import com.teachloop.shared.error.ErrorCode;
import com.teachloop.shared.storage.BlobStore;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 2026-10-31：113 张定稿 WebP 接入产品——按 content_id 的约定路径、视图字段、图片接口。
 *
 * <p>锁住四件容易漂的事：
 * <ol>
 *   <li>key 就是 {@code vocab/images/<content_id>.webp}（导入脚本与后端必须同一个约定）；</li>
 *   <li>地址里的 id 取自 **delta**（稳定），而不是读路径拿到的包内位置号——
 *       包重建会让位置号顺移，用位置号拼文件名会把别人的图挂到这个词上；</li>
 *   <li>没有图时视图里**没有** {@code imageUrl} 字段（前端据此不渲染、不占位）；</li>
 *   <li>缺图接口返回 404（不是 204、不是空 200）。</li>
 * </ol>
 */
class VocabImageStoreTest {

    @TempDir
    Path tempDir;

    /** 最小 BlobStore 替身：只记 key → 字节。 */
    private static final class FakeBlobs implements BlobStore {
        private final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public String store(String key, byte[] content, String contentType) {
            objects.put(key, content);
            return key;
        }

        @Override
        public Optional<byte[]> load(String key) {
            return Optional.ofNullable(objects.get(key));
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }
    }

    private static final byte[] WEBP = new byte[] {'R', 'I', 'F', 'F'};

    private DeltaContentStore delta() {
        return new DeltaContentStore(tempDir.resolve("delta.db").toString(), "vocab-content-v2.5");
    }

    @Test
    @DisplayName("key 约定：vocab/images/<contentId>.webp；接口路径 /api/vocab/contents/<id>/image")
    void keyConvention() {
        assertThat(VocabImageStore.keyFor(1000000022L)).isEqualTo("vocab/images/1000000022.webp");
        assertThat(VocabImageStore.urlFor(1000000022L))
                .isEqualTo("/api/vocab/contents/1000000022/image");
    }

    @Test
    @DisplayName("按词形给地址：文件在才给，文件不在 / 词形没有 delta 行都回 empty")
    void urlResolvedFromDeltaId() {
        DeltaContentStore delta = delta();
        long appleId = delta.put("en", "apple", "{\"schemaVersion\":2}", "AI");
        FakeBlobs blobs = new FakeBlobs();
        blobs.store(VocabImageStore.keyFor(appleId), WEBP, "image/webp");
        VocabImageStore images = new VocabImageStore(blobs, delta, true);

        assertThat(images.url("en", "apple"))
                .contains("/api/vocab/contents/" + appleId + "/image");
        // 有 delta 行但没有图文件 → 没有地址（不会给前端一个必然 404 的 URL）
        assertThat(images.url("en", "pear")).isEmpty();
        // 词形空白 / null 不抛异常
        assertThat(images.url("en", "")).isEmpty();
        assertThat(images.url("en", null)).isEmpty();
        delta.close();
    }

    @Test
    @DisplayName("地址里的 id 来自 delta，不是内容包的位置号（包重建后图片不会串词）")
    void urlUsesStableDeltaIdNotPackPosition() {
        DeltaContentStore delta = delta();
        long appleId = delta.put("en", "apple", "{\"schemaVersion\":2}", "AI");
        long zebraId = delta.put("en", "zebra", "{\"schemaVersion\":2}", "AI");
        assertThat(appleId).isLessThan(zebraId);

        FakeBlobs blobs = new FakeBlobs();
        blobs.store(VocabImageStore.keyFor(appleId), WEBP, "image/webp");
        VocabImageStore images = new VocabImageStore(blobs, delta, true);

        // 假如有人把文件按「包内位置号 1」命名，是不会被命中的：只认 delta id。
        assertThat(images.exists(1L)).isFalse();
        assertThat(images.url("en", "apple"))
                .contains("/api/vocab/contents/" + appleId + "/image");
        assertThat(images.url("en", "zebra")).isEmpty();
        delta.close();
    }

    @Test
    @DisplayName("teachloop.vocab.images.enabled=false 时整条链路都当没有图")
    void disabledMeansNoImage() {
        DeltaContentStore delta = delta();
        long appleId = delta.put("en", "apple", "{\"schemaVersion\":2}", "AI");
        FakeBlobs blobs = new FakeBlobs();
        blobs.store(VocabImageStore.keyFor(appleId), WEBP, "image/webp");
        VocabImageStore images = new VocabImageStore(blobs, delta, false);

        assertThat(images.exists(appleId)).isFalse();
        assertThat(images.url("en", "apple")).isEmpty();
        assertThat(images.load(appleId)).isEmpty();
        delta.close();
    }

    @Test
    @DisplayName("接口：有图 200 + image/webp + 字节 + 7 天缓存；缺图 404")
    void endpointServesWebpAnd404s() {
        DeltaContentStore delta = delta();
        long appleId = delta.put("en", "apple", "{\"schemaVersion\":2}", "AI");
        FakeBlobs blobs = new FakeBlobs();
        blobs.store(VocabImageStore.keyFor(appleId), WEBP, "image/webp");
        ContentImageEndpoint endpoint = new ContentImageEndpoint(new VocabImageStore(blobs, delta, true));

        ResponseEntity<byte[]> ok = endpoint.image(appleId);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("image/webp"));
        assertThat(ok.getBody()).isEqualTo(WEBP);
        assertThat(ok.getHeaders().getCacheControl()).contains("max-age=604800");

        assertThat(catchThrowable(() -> endpoint.image(appleId + 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code())
                        .isEqualTo(ErrorCode.NOT_FOUND));
        delta.close();
    }
}
