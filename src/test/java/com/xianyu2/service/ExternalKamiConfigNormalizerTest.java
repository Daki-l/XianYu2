package com.xianyu2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyu2.controller.dto.KamiConfigReqDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalKamiConfigNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizesOverEscapedApiSettingsAndBareNumericTemplatePlaceholders() throws Exception {
        KamiConfigReqDTO request = new KamiConfigReqDTO();
        request.setExternalApiUrl("https\\://xianyu.linow\\.us.kg/faka/order");
        request.setExternalApiHeaders("{\\\"Authorization\\\":\\\"Bearer token\\\",\\\"Content-Type\\\":\\\"application/json\\\"}");
        request.setExternalApiBody("{\\\"orderId\\\":\\\"{orderId}\\\",\\\"quantity\\\":{quantity},\\\"requestToken\\\":\\\"{requestToken}\\\"}");

        ExternalKamiConfigNormalizer.normalize(request, objectMapper);

        assertThat(request.getExternalApiUrl()).isEqualTo("https://xianyu.linow.us.kg/faka/order");
        assertThat(request.getExternalApiHeaders())
                .isEqualTo("{\"Authorization\":\"Bearer token\",\"Content-Type\":\"application/json\"}");
        assertThat(request.getExternalApiBody())
                .isEqualTo("{\"orderId\":\"{orderId}\",\"quantity\":{quantity},\"requestToken\":\"{requestToken}\"}");
        assertThat(ExternalKamiConfigNormalizer.readBodyTemplate(
                request.getExternalApiBody(), objectMapper).path("quantity").asText())
                .isEqualTo("{quantity}");
        String renderedBody = request.getExternalApiBody()
                .replace("{orderId}", "order-42")
                .replace("{quantity}", "2")
                .replace("{requestToken}", "request-abc");
        assertThat(objectMapper.readTree(renderedBody).path("quantity").asInt()).isEqualTo(2);
    }

    @Test
    void preservesInvalidContentForExistingValidation() {
        KamiConfigReqDTO request = new KamiConfigReqDTO();
        request.setExternalApiHeaders("not-json");
        request.setExternalApiBody("[]");

        ExternalKamiConfigNormalizer.normalize(request, objectMapper);

        assertThat(request.getExternalApiHeaders()).isEqualTo("not-json");
        assertThat(request.getExternalApiBody()).isEqualTo("[]");
    }
}
