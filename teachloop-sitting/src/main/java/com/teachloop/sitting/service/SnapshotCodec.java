package com.teachloop.sitting.service;

import com.teachloop.shared.port.Ports;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 试卷快照存成 JSON：布置时固化，之后改标准答案不影响已布置的场次。 */
@Component
public class SnapshotCodec {

    private final ObjectMapper mapper;

    public SnapshotCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Ports.PaperSnapshot snapshot) {
        return mapper.writeValueAsString(snapshot);
    }

    public Ports.PaperSnapshot read(String json) {
        return mapper.readValue(json, Ports.PaperSnapshot.class);
    }
}
