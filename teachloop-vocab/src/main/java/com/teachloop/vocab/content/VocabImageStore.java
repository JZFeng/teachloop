package com.teachloop.vocab.content;

import com.teachloop.shared.storage.BlobStore;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 词卡配图（2026-10-31，113 张定稿 WebP 接入产品）。
 *
 * <p><b>为什么按 content_id 存、用约定路径而不是新建映射表</b>：图片是「某条内容」的附属物，
 * 而 delta 的 {@code content_id} 已经是稳定主键（从 1_000_000_000 起，单调递增、不复用）。
 * 一张图 = 一个文件，文件名就是 content_id，因此不需要一张映射表：没有「表里有行但文件丢了」
 * 与「文件在但表里没行」两种不一致，也不需要迁移。代价是改图 = 覆盖文件，无审计（本任务不需要）。
 *
 * <p><b>存储走 {@link BlobStore}</b>（T386 的既有端口），不直接写文件系统：
 * 本地实现是 {@code teachloop.storage.local-dir}（dev/qa 为 {@code ./uploads} /
 * {@code .envs/qa/uploads}），生产切 S3 时同一份代码继续可用。
 *
 * <p>key 形如 {@code vocab/images/1000000022.webp}，与 TTS 的 {@code vocab/tts/<hash>.wav}
 * 同一前缀习惯。**图不存在时返回 empty，不抛异常**：绝大多数词没有配图，缺图是正常路径。
 */
@Component
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true", matchIfMissing = true)
public class VocabImageStore {

    /** 约定路径前缀；改动它等于让存量图片全部失效，只能与迁移脚本一起改。 */
    static final String KEY_PREFIX = "vocab/images/";
    static final String EXTENSION = ".webp";

    private final BlobStore blobs;
    /** 词形 → delta content_id；null = 这层没接（单测 / 只要文件读写）。 */
    private final DeltaContentStore delta;

    @Autowired
    public VocabImageStore(BlobStore blobs, DeltaContentStore delta,
                           @Value("${teachloop.vocab.images.enabled:true}") boolean enabled) {
        this.blobs = enabled ? blobs : null;
        this.delta = enabled ? delta : null;
    }

    /** 不接 delta 的装配：只能按 id 读写文件，{@link #url(String, String)} 永远 empty。 */
    public VocabImageStore(BlobStore blobs, boolean enabled) {
        this(blobs, null, enabled);
    }

    /** 某条内容的图片 key（无论文件在不在）。 */
    static String keyFor(long contentId) {
        return KEY_PREFIX + contentId + EXTENSION;
    }

    /** 图片在不在；{@code contentId <= 0}（词条还没准备内容）一律当没有。 */
    public boolean exists(long contentId) {
        if (blobs == null || contentId <= 0) {
            return false;
        }
        return blobs.exists(keyFor(contentId));
    }

    /**
     * 内容视图里给前端用的地址；没有图时返回 empty，前端据此**不渲染、不占位**。
     *
     * <p><b>为什么入参是词形、不是读路径拿到的 contentId</b>（2026-10-31 实测踩到）：
     * 这 113 个词由**内容包**优先命中，而包里的 {@code contentId} 是「按词形排序后的 1..N 排列」
     * （见 {@code ContentPackIndex} 的装载校验：{@code contentId 恰好是 1..count 的一个排列}），
     * 只有 delta 的 id 才是 1_000_000_000 起的稳定值，而图片文件正是按 delta id 命名的
     * （{@code scripts/vocab-images-import.py}）。若直接用读路径的 contentId 拼文件名，
     * 包重建一次（增一个词，后面所有 id 顺移）就会**静默把别人的图挂到这个词上**。
     * 词形是稳定的身份，所以这里按 {@code (language, form)} 反查 delta id，再按那个 id 拼 URL
     * ——URL 里的 id 始终是「图片所属的那条内容」的 id。
     *
     * <p>返回站内相对路径而不是把图片塞进 JSON：内容 JSON 的体积与解析成本不变，
     * 浏览器按 URL 走 HTTP 缓存，重复复习同一张卡不再重传图片。
     */
    public Optional<String> url(String language, String normalizedForm) {
        return deltaId(language, normalizedForm).filter(this::exists).map(VocabImageStore::urlFor);
    }

    /**
     * 词形 → delta content_id（图片文件名的来源）。
     *
     * <p>只认当前 {@code prompt-version} 的 delta 行：内容升到 v3 之后，v2.5 的旧图不该再挂上去。
     * 词只在内容包里、没有 delta 行时返回 empty（本任务的 113 个词都有 delta 行）。
     */
    Optional<Long> deltaId(String language, String normalizedForm) {
        if (delta == null || normalizedForm == null || normalizedForm.isBlank()) {
            return Optional.empty();
        }
        return delta.get(language, normalizedForm).map(StoredContent::id).filter(id -> id > 0);
    }

    /** 图片接口的路径；与 {@link ContentImageEndpoint} 的映射必须一致。 */
    public static String urlFor(long contentId) {
        return "/api/vocab/contents/" + contentId + "/image";
    }

    /** 图片字节；文件缺失返回 empty（接口层映射 404）。 */
    public Optional<byte[]> load(long contentId) {
        if (blobs == null || contentId <= 0) {
            return Optional.empty();
        }
        return blobs.load(keyFor(contentId));
    }
}
