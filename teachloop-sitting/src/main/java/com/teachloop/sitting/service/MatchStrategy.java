package com.teachloop.sitting.service;

/** T053：客观题匹配策略；新增策略只需注册，不改判分主流程。 */
public interface MatchStrategy {

    String id();

    boolean matches(String expected, String given);
}
