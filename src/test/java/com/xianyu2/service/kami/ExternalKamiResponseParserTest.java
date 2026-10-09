package com.xianyu2.service.kami;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalKamiResponseParserTest {

    private final ExternalKamiResponseParser parser = new ExternalKamiResponseParser(new ObjectMapper());

    @Test
    void parsesCardsAndOptionalSupplierOrderId() {
        ExternalKamiResponseParser.ParsedResponse result = parser.parseResponse(
                "{\"data\":{\"cards\":[\"A\",\"B\"],\"supplierOrder\":9988}}",
                "data.cards", "data.supplierOrder", 2);

        assertThat(result.contents()).containsExactly("A", "B");
        assertThat(result.externalOrderId()).isEqualTo("9988");
    }

    @Test
    void leavesSupplierOrderIdEmptyWhenPathIsNotConfigured() {
        ExternalKamiResponseParser.ParsedResponse result = parser.parseResponse(
                "{\"data\":{\"cards\":[\"A\"]}}", "data.cards", null, 1);

        assertThat(result.externalOrderId()).isNull();
    }
}
