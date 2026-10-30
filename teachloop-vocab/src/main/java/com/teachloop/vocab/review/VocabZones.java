package com.teachloop.vocab.review;

import com.teachloop.shared.port.Ports;
import com.teachloop.shared.time.ZoneResolution;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 011 / T557 / FR-226 / FR-228：学生可见的「今天」口径集中在这里。
 *
 * <p>优先级：{@code X-Timezone} 请求头 &gt; {@code users.timezone} &gt; 产品默认
 * （{@code teachloop.timezone.default}，默认 {@code Asia/Shanghai}）。非法值只记一条 warning
 * 并继续回落，**绝不抛 500**（AC-99.2）——时区错了只影响一天的计数起点，不该让复习页打不开。
 *
 * <p>只给两处端点用：{@link ReviewQueueEndpoint#countReviewedToday} 与
 * {@link com.teachloop.vocab.quiz.QuizEndpoint} 的「今天复习过的词」。
 * AI 生成日预算（{@code VocabularyGenerationService}）**不**经过这里，保持产品时区（FR-228）。
 *
 * <p>{@code UserZonePort} 用 {@link ObjectProvider} 取：vocab 模块的单测上下文里没有
 * identity 装配时按「未设置」处理，行为等同回落产品默认，不会因为缺 bean 起不来。
 */
@Component
public class VocabZones {

    private static final Logger log = LoggerFactory.getLogger(VocabZones.class);

    private final ObjectProvider<Ports.UserZonePort> userZones;
    private final Clock clock;
    private final ZoneId productDefault;

    public VocabZones(ObjectProvider<Ports.UserZonePort> userZones, Clock clock,
                      @Value("${teachloop.timezone.default:Asia/Shanghai}") String productDefault) {
        this.userZones = userZones;
        this.clock = clock;
        this.productDefault = ZoneResolution.resolve(null, Optional.empty(), ZoneId.of(productDefault));
    }

    /** 解析本次请求的「今天」用哪个时区。 */
    public ZoneId resolve(String header, UUID userId) {
        String requested = header == null ? null : header.trim();
        if (requested != null && ZoneResolution.parse(requested) == null) {
            log.warn("ignoring invalid X-Timezone header: {}", abbreviate(header));
        }
        Optional<String> stored = Optional.empty();
        Ports.UserZonePort port = userId == null ? null : userZones.getIfAvailable();
        if (port != null) {
            try {
                stored = port.of(userId).map(Ports.UserZone::iana);
            } catch (RuntimeException ex) {
                // 资料读失败按「未设置」处理：今天少一个个人偏好，不影响复习本身。
                log.warn("user timezone lookup failed user={}: {}", userId, ex.toString());
            }
        }
        if (stored.isPresent() && ZoneResolution.parse(stored.get()) == null) {
            log.warn("ignoring invalid stored timezone user={}: {}", userId, abbreviate(stored.get()));
        }
        return ZoneResolution.resolve(requested, stored, productDefault);
    }

    /** 该时区当天的 0 点（UTC 时刻）。DST 日不是 24 小时，交给 {@link LocalDate#atStartOfDay} 处理。 */
    public Instant dayStart(ZoneId zone) {
        ZoneId effective = zone == null ? productDefault : zone;
        return LocalDate.ofInstant(clock.instant(), effective).atStartOfDay(effective).toInstant();
    }

    /** 端口调用方已经把 IANA 串解析好了（app 层做），这里只按它算当日 0 点。 */
    public Instant dayStartOf(String iana) {
        ZoneId zone = ZoneResolution.resolve(null, Optional.ofNullable(iana), productDefault);
        return dayStart(zone);
    }

    /** 日志里只留前 64 字符：恶意超长头不能把日志冲爆（AC-99.2 的「超长串」）。 */
    private static String abbreviate(String raw) {
        if (raw == null) {
            return "null";
        }
        return raw.length() <= 64 ? raw : raw.substring(0, 64) + "…";
    }
}
