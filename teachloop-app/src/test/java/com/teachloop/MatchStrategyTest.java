package com.teachloop;

import static org.assertj.core.api.Assertions.assertThat;

import com.teachloop.sitting.service.MatchStrategies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T053：四种匹配策略的边界。 */
class MatchStrategyTest {

    @Test
    @DisplayName("exact：大小写与空白都敏感")
    void exact() {
        assertThat(MatchStrategies.EXACT.matches("Hello", "Hello")).isTrue();
        assertThat(MatchStrategies.EXACT.matches("Hello", "hello")).isFalse();
        assertThat(MatchStrategies.EXACT.matches("Hello", " Hello ")).isFalse();
    }

    @Test
    @DisplayName("normalized：忽略大小写、空白与标点")
    void normalized() {
        assertThat(MatchStrategies.NORMALIZED.matches("Hello, World!", "hello world")).isTrue();
        assertThat(MatchStrategies.NORMALIZED.matches("He   goes", "he goes.")).isTrue();
        assertThat(MatchStrategies.NORMALIZED.matches("Hello", "Hallo")).isFalse();
    }

    @Test
    @DisplayName("choice：答案字母集合无序比较")
    void choice() {
        assertThat(MatchStrategies.CHOICE.matches("AB", "BA")).isTrue();
        assertThat(MatchStrategies.CHOICE.matches("A,B", "a b")).isTrue();
        assertThat(MatchStrategies.CHOICE.matches("AB", "A")).isFalse();
    }

    @Test
    @DisplayName("set：多写法按分隔符拆分，顺序无关")
    void set() {
        assertThat(MatchStrategies.SET.matches("color; colour", "colour color")).isTrue();
        assertThat(MatchStrategies.SET.matches("a,b", "b, a")).isTrue();
        assertThat(MatchStrategies.SET.matches("a,b", "a,c")).isFalse();
    }
}
