package com.teachloop.grading.engine;

import com.teachloop.shared.port.Ports;

/** 判分入口：与解析入口分离（宪法第五条）。 */
public interface GradingEngine {
    Ports.GradingResult grade(Ports.WorkItem item);
}
