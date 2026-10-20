package com.teachloop.sitting.storage;

import java.util.Optional;

/**
 * 附件存储抽象（T067）。开发用 {@link LocalStorage}，生产切 OSS 只换实现，不改 sitting 的业务代码。
 *
 * <p>key 由本模块生成（包含 offering / 随机 id），不含用户可控路径；实现必须防止路径穿越。
 */
public interface StorageBackend {

    /**
     * 保存文件。
     *
     * @param key         本次存储的 key（相对路径）
     * @param content     文件字节
     * @param contentType 内容类型（仅 OSS 等需要元数据的实现使用）
     * @return 实际可读取的 key
     */
    String store(String key, byte[] content, String contentType);

    /** 读取文件；不存在返回 empty（下载接口据此给 404）。 */
    Optional<byte[]> load(String key);
}
