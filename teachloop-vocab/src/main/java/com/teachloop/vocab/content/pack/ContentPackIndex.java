package com.teachloop.vocab.content.pack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 内容包索引：常驻内存的 28 字节/条目录（decisions/017 P2 / work/plan-content-tts.md §2）。
 *
 * <p>每条大端：{@code hash64(8) + offset(8) + length(4) + contentId(8)}，按 unsigned hash64 升序、
 * 同 hash 按 contentId 升序。{@code hash64 = sha256(UTF-8(language + "|" + normalizedForm)) 的前 8 字节}
 * 按大端解释为无符号 long（见 {@link ContentPackReader#hash64}）。
 *
 * <p>装载时一次性校验：字节数 = count*28、hash 非降序、offset/length 落在 pack 内、contentId 恰好是
 * 1..count 的一个排列。任一条不满足就整体拒绝，绝不把半份索引装进内存。
 *
 * <p>内存：每条 40 字节（hash / offset / id 各 8，length 4，按 id 的 offset 8、length 4）。
 * 10 万词 ≈ 4.0MB，在 plan §4.1.6 的 ≤8MB 目标内；不额外保留 pack 原文。
 */
final class ContentPackIndex {

    /** idx 每条记录固定 28 字节。 */
    static final int RECORD_BYTES = 28;

    /** 每条常驻内存字节数：hash(8) + offset(8) + length(4) + contentId(8) + idOffset(8) + idLength(4)。 */
    static final long HEAP_BYTES_PER_ENTRY = 3L * Long.BYTES + 2L * Integer.BYTES + Long.BYTES;

    private final long[] hashes;
    private final long[] offsets;
    private final int[] lengths;
    private final long[] ids;
    private final long[] idOffsets;
    private final int[] idLengths;

    private ContentPackIndex(long[] hashes, long[] offsets, int[] lengths, long[] ids,
                             long[] idOffsets, int[] idLengths) {
        this.hashes = hashes;
        this.offsets = offsets;
        this.lengths = lengths;
        this.ids = ids;
        this.idOffsets = idOffsets;
        this.idLengths = idLengths;
    }

    static ContentPackIndex parse(byte[] raw, int count, long packSize) {
        long expectedBytes = (long) count * RECORD_BYTES;
        if (raw.length != expectedBytes) {
            throw new ContentPackException("idx 字节数 " + raw.length + " 与 manifest count=" + count
                    + " 期望的 " + expectedBytes + "（count*28）不一致");
        }
        long[] hashes = new long[count];
        long[] offsets = new long[count];
        int[] lengths = new int[count];
        long[] ids = new long[count];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < count; i++) {
            hashes[i] = buffer.getLong();
            offsets[i] = buffer.getLong();
            lengths[i] = buffer.getInt();
            ids[i] = buffer.getLong();
        }

        long[] idOffsets = new long[count + 1];
        int[] idLengths = new int[count + 1];
        Arrays.fill(idOffsets, -1L);
        boolean[] seenIds = new boolean[count + 1];
        for (int i = 0; i < count; i++) {
            long offset = offsets[i];
            int length = lengths[i];
            long id = ids[i];
            if (i > 0) {
                int order = Long.compareUnsigned(hashes[i], hashes[i - 1]);
                if (order < 0) {
                    throw new ContentPackException("idx 第 " + i + " 条的 hash64="
                            + Long.toUnsignedString(hashes[i]) + " 小于上一条 "
                            + Long.toUnsignedString(hashes[i - 1]) + "（必须按 unsigned 升序）");
                }
                if (order == 0 && id <= ids[i - 1]) {
                    throw new ContentPackException("idx 第 " + i + " 条与上一条 hash64 相同，"
                            + "但 contentId=" + id + " 未升序（要求同 hash 按 contentId 升序）");
                }
            }
            if (offset < 0 || length <= 0) {
                throw new ContentPackException("idx 第 " + i + " 条 offset=" + offset
                        + " length=" + length + " 不合法（offset>=0、length>0）");
            }
            if (offset > packSize - length) {
                throw new ContentPackException("idx 第 " + i + " 条 offset=" + offset + " length=" + length
                        + " 超出 pack 大小 " + packSize + "（包被截断或 idx 与 pack 不配套）");
            }
            if (id < 1 || id > count) {
                throw new ContentPackException("idx 第 " + i + " 条 contentId=" + id
                        + " 超出 1.." + count + "（包内 id 连续递增）");
            }
            int slot = (int) id;
            if (seenIds[slot]) {
                throw new ContentPackException("idx contentId=" + id + " 重复（包内 id 必须唯一）");
            }
            seenIds[slot] = true;
            idOffsets[slot] = offset;
            idLengths[slot] = length;
        }
        return new ContentPackIndex(hashes, offsets, lengths, ids, idOffsets, idLengths);
    }

    int count() {
        return hashes.length;
    }

    long hashAt(int index) {
        return hashes[index];
    }

    long offsetAt(int index) {
        return offsets[index];
    }

    int lengthAt(int index) {
        return lengths[index];
    }

    long idAt(int index) {
        return ids[index];
    }

    /** 第一条 unsigned hash >= 目标的下标（标准下界二分）；命中区间从这里开始。 */
    int lowerBound(long hash) {
        int low = 0;
        int high = hashes.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (Long.compareUnsigned(hashes[mid], hash) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** 包内 id 的 offset；没有这条记录时返回 -1。 */
    long offsetForId(long contentId) {
        if (contentId < 1 || contentId > hashes.length) {
            return -1L;
        }
        return idOffsets[(int) contentId];
    }

    int lengthForId(long contentId) {
        if (contentId < 1 || contentId > hashes.length) {
            return -1;
        }
        return idLengths[(int) contentId];
    }

    /** 常驻内存估算（不含数组头与 manifest 字符串）。 */
    long heapBytes() {
        return (long) hashes.length * HEAP_BYTES_PER_ENTRY;
    }
}
