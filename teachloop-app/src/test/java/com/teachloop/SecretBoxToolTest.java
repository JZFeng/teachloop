package com.teachloop;

import com.teachloop.identity.ai.SecretBox;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 一次性运维小工具：把 {@code ~/.teachloop-smtp.txt} 里的授权码加密成 {@code secret_cipher} 输出。
 *
 * <p>只用于没有管理员、又需要在环境里预置通道的场合（比如 qa 首次部署）。
 * 默认不跑：{@code -Dsecretbox.tool=true}。输出的是密文，明文不会打印。
 */
@EnabledIfSystemProperty(named = "secretbox.tool", matches = "true")
class SecretBoxToolTest {

    @Test
    void printCipher() throws Exception {
        String plain = Files.readString(Path.of(System.getProperty("user.home"), ".teachloop-smtp.txt")).trim();
        SecretBox box = new SecretBox(System.getProperty("secretbox.key", "teachloop-local-dev-secret"));
        System.out.println("CIPHER=" + box.encrypt(plain));
    }
}
