package com.teachloop.vocab.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T429：SQLite delta 的幂等写、按词形/按 id 读取、prompt 版本过滤与重启后仍可命中。 */
class DeltaContentStoreTest {

    @TempDir
    Path tempDir;

    private DeltaContentStore store(String version) {
        return new DeltaContentStore(tempDir.resolve("delta.db").toString(), version);
    }

    @Test
    @DisplayName("写入返回 10 亿段 id；同词重复写幂等复用同一个 id；按词形与 id 都能读")
    void putIsIdempotent() {
        DeltaContentStore delta = store("v1");
        long id = delta.put("en", "apple", "{\"word\":\"apple\",\"v\":1}", "AI");
        assertThat(id).isGreaterThanOrEqualTo(DeltaContentStore.DELTA_ID_START);

        long again = delta.put("en", "apple", "{\"word\":\"apple\",\"v\":2}", "AI");
        assertThat(again).isEqualTo(id);

        assertThat(delta.get("en", "apple")).get()
                .extracting(StoredContent::json).asString().contains("\"v\":2");
        assertThat(delta.getById(id)).get()
                .extracting(StoredContent::json).asString().contains("\"v\":2");
        assertThat(delta.get("en", "pear")).isEmpty();
        assertThat(delta.getById(123)).isEmpty();
    }

    @Test
    @DisplayName("换 prompt 版本后旧 delta 不再命中（换提示词 = 出新包 + 升配置）")
    void promptVersionFilters() {
        DeltaContentStore oldStore = store("v1");
        long id = oldStore.put("en", "apple", "{\"word\":\"apple\"}", "AI");
        oldStore.close();

        DeltaContentStore newStore = store("v2");
        assertThat(newStore.get("en", "apple")).isEmpty();
        assertThat(newStore.getById(id)).isEmpty();
        // 新版本写入自己的行；唯一键含 prompt_version，不复用旧行
        long newId = newStore.put("en", "apple", "{\"word\":\"apple\",\"v\":2}", "AI");
        assertThat(newId).isNotEqualTo(id);
        assertThat(newStore.get("en", "apple")).isPresent();
    }

    @Test
    @DisplayName("重启后仍能命中：delta 是持久文件，不靠进程内索引")
    void survivesRestart() {
        DeltaContentStore first = store("v1");
        long id = first.put("en", "kettle", "{\"word\":\"kettle\"}", "AI");
        first.close();

        DeltaContentStore second = store("v1");
        assertThat(second.get("en", "kettle")).get()
                .extracting(StoredContent::id).isEqualTo(id);
        assertThat(second.getById(id)).isPresent();
    }

    @Test
    @DisplayName("ContentService：词形规范化、命中统计、写法落 delta")
    void serviceResolvesThroughDelta() {
        DeltaContentStore delta = store("v1");
        ContentService service = new ContentService(List.of(delta), delta, "v1");

        long id = service.put("en", "  Apple ", "{\"word\":\"apple\"}", "AI");
        // 写时已规范化为 apple；大小写/空格不同的查询同样命中
        assertThat(service.get("en", "APPLE")).get()
                .extracting(StoredContent::id).isEqualTo(id);
        assertThat(service.get("en", " Pear ")).isEmpty();

        ContentStats stats = service.stats();
        assertThat(stats.hits()).isEqualTo(1);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.puts()).isEqualTo(1);
    }
}
