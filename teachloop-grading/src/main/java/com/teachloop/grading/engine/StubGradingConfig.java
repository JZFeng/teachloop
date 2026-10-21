package com.teachloop.grading.engine;

import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!prod")
public class StubGradingConfig {

    @Bean
    @ConditionalOnProperty(name = "teachloop.grading.engine", havingValue = "stub", matchIfMissing = true)
    public GradingEngine stubGradingEngine() {
        return new StubGradingEngine(new BigDecimal("0.75"));
    }
}
