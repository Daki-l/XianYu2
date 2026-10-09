package com.xianyu2.service.kami;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalKamiGatewayTest {

    @Test
    void rendersBareQuantityPlaceholderAsJsonNumber() throws Exception {
        String body = ExternalKamiGateway.renderBody(
                "{\"orderId\":\"{orderId}\",\"quantity\":{quantity},\"requestToken\":\"{requestToken}\"}",
                "order-42", 2, "request-abc", new ObjectMapper());

        assertThat(body).isEqualTo(
                "{\"orderId\":\"order-42\",\"quantity\":2,\"requestToken\":\"request-abc\"}");
    }

    @Test
    void rendersAccountScopedClientOrderNumber() throws Exception {
        String body = ExternalKamiGateway.renderBody(
                "{\"accountId\":{accountId},\"clientOrderNo\":\"{clientOrderNo}\"}",
                12L, "order-42", "12_order-42", 1, "request-abc", new ObjectMapper());

        assertThat(body).isEqualTo("{\"accountId\":12,\"clientOrderNo\":\"12_order-42\"}");
    }
}
