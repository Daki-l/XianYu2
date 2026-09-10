package com.xianyu2.config.rag;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicAIChatClientManagerTest {

    @Test
    void aiHttpClientUsesAnAbsoluteCallTimeout() {
        DynamicAIChatClientManager manager = new DynamicAIChatClientManager();
        ReflectionTestUtils.setField(manager, "requestTimeoutSeconds", 120);

        OkHttpClient client = manager.aiHttpClient();

        assertEquals(120_000, client.callTimeoutMillis());
        assertEquals(120_000, client.readTimeoutMillis());
        assertEquals(120_000, client.writeTimeoutMillis());
    }
}
