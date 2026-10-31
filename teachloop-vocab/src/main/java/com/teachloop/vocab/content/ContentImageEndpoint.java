package com.teachloop.vocab.content;

import com.teachloop.shared.error.ApiException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 词卡配图接口（2026-10-31）：{@code GET /api/vocab/contents/{contentId}/image} → WebP 字节。
 *
 * <p>为什么单独一个接口，而不是把 base64 塞进内容 JSON：JSON 会为每张图多背 33% 体积、
 * 且每次拉内容都要重传一遍；图片走 URL 才能吃 HTTP 缓存。
 *
 * <p><b>缺图返回 404</b>（不是 204、不是占位图）：绝大多数词没有配图，404 是正常答案；
 * 内容视图里的 {@code imageUrl} 只在图片真的存在时才出现，所以前端正常不会请求到 404。
 *
 * <p>缓存头 {@code max-age=7d}：图片按 content_id 命名、内容不变则图不变；
 * 换图走覆盖同名文件，最长 7 天后自然失效（qa/dev 验收期间够用，不做 ETag 协商）。
 */
@RestController
@RequestMapping("/api/vocab/contents")
@ConditionalOnProperty(name = "teachloop.vocab.enabled", havingValue = "true")
public class ContentImageEndpoint {

    private static final MediaType WEBP = MediaType.parseMediaType("image/webp");

    private final VocabImageStore images;

    public ContentImageEndpoint(VocabImageStore images) {
        this.images = images;
    }

    @GetMapping("/{contentId}/image")
    public ResponseEntity<byte[]> image(@PathVariable long contentId) {
        Optional<byte[]> bytes = images.load(contentId);
        if (bytes.isEmpty()) {
            throw ApiException.notFound("image not found for content " + contentId);
        }
        return ResponseEntity.ok()
                .contentType(WEBP)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(bytes.get());
    }
}
