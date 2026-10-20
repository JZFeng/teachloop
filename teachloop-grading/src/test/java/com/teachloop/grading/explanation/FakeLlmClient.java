package com.teachloop.grading.explanation;

import com.teachloop.grading.ai.LlmClient;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 测试用模型替身：按顺序返回响应，或固定抛错。记录每次请求便于断言 prompt。 */
public final class FakeLlmClient implements LlmClient {

    public final List<LlmRequest> requests = new ArrayList<>();
    private final Deque<LlmResponse> responses = new ArrayDeque<>();
    private RuntimeException failure;

    private FakeLlmClient() {
    }

    public static FakeLlmClient responding(LlmResponse... values) {
        FakeLlmClient client = new FakeLlmClient();
        client.responses.addAll(List.of(values));
        return client;
    }

    public static FakeLlmClient failing(RuntimeException failure) {
        FakeLlmClient client = new FakeLlmClient();
        client.failure = failure;
        return client;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        requests.add(request);
        if (failure != null) {
            throw failure;
        }
        return responses.removeFirst();
    }
}
