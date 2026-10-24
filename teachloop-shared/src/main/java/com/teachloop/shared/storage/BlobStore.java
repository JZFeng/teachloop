package com.teachloop.shared.storage;

import java.util.Optional;

/**
 * 附件与对象存储端口（T386）。业务模块只依赖这个接口，不依赖具体实现，也不依赖任何厂商 SDK。
 *
 * <p>key 由业务模块生成（例如 {@code submissions/<offeringId>/<uuid>.<ext>}），必须是相对 key：
 * 不含 {@code ..}、不以 {@code /} 开头。实现必须自行防止路径穿越，并把「对象不存在」表达为
 * {@link Optional#empty()} 或 {@code false}，而不是抛异常；存储后端不可用时才抛
 * {@code StorageUnavailableException}（接口层映射为 503）。
 *
 * <p>实现：{@code LocalStorage}（开发默认，文件系统）与 {@code S3BlobStore}（生产，S3 兼容协议，
 * 阿里云 OSS 与本地 MinIO 都可用）。凭据只由部署层通过环境变量提供，不经过业务代码。
 */
public interface BlobStore {

    /**
     * 保存对象。同一个 key 再次写入即覆盖。
     *
     * @param key         相对 key
     * @param content     文件字节
     * @param contentType 内容类型（S3 实现写入对象元数据；本地实现忽略）
     * @return 实际可读取的 key（实现可能加前缀）
     */
    String store(String key, byte[] content, String contentType);

    /** 读取对象；不存在返回 empty。存储后端不可用时抛运行时异常，由接口层映射为 503。 */
    Optional<byte[]> load(String key);

    /** 对象是否存在；不存在返回 false。 */
    boolean exists(String key);
}
