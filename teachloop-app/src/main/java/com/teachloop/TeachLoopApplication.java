package com.teachloop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 唯一启动模块。放在 com.teachloop 根包下，组件、实体、仓库都按包自动扫描。
 */
@SpringBootApplication
@EnableScheduling
public class TeachLoopApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeachLoopApplication.class, args);
    }
}
