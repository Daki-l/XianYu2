package com.xianyu2.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XianyuWebSocketClientHistoryTest {

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void stopsAfterAnEmptyPageEvenWhenThePlatformClaimsThereAreMorePages() {
        StubHistoryClient client = client(cursor -> page(true, cursor - 1, List.of()));

        assertTrue(client.listConversationHistory("buyer@goofish", 500).isEmpty());
        assertEquals(1, client.requestCount);
    }

    @Test
    void stopsWhenTheNextCursorWasAlreadyVisited() {
        long initialCursor = 9007199254740991L;
        StubHistoryClient client = client(cursor -> page(true, initialCursor, List.of(model("one"))));

        assertEquals(1, client.listConversationHistory("buyer", 500).size());
        assertEquals(1, client.requestCount);
    }

    @Test
    void limitsAHistoryTraversalWithContinuouslyChangingCursors() {
        StubHistoryClient client = client(cursor -> page(true, cursor - 1, List.of(model("message-" + cursor))));

        assertEquals(XianyuWebSocketClient.MAX_HISTORY_PAGES,
                client.listConversationHistory("buyer", 500).size());
        assertEquals(XianyuWebSocketClient.MAX_HISTORY_PAGES, client.requestCount);
    }

    @Test
    void rejectsOneOversizedPageBeforeAddingAnyOfItsModels() {
        String oversized = "x".repeat((int) XianyuWebSocketClient.MAX_HISTORY_PAGE_RESPONSE_BYTES + 1);
        StubHistoryClient client = client(cursor -> page(true, cursor - 1, List.of(model("one")), oversized));

        assertTrue(client.listConversationHistory("buyer", 500).isEmpty());
        assertEquals(1, client.requestCount);
    }

    @Test
    void stopsBeforeHistoryResponsesExceedTheTotalSizeLimit() {
        String pagePayload = "x".repeat(600_000);
        StubHistoryClient client = client(cursor -> page(true, cursor - 1, List.of(model("message-" + cursor)), pagePayload));

        List<Map<String, Object>> result = client.listConversationHistory("buyer", 500);

        assertEquals(13, result.size());
        assertEquals(14, client.requestCount);
    }

    private StubHistoryClient client(LongFunction<Map<String, Object>> pages) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return new StubHistoryClient(pages, executor);
    }

    private Map<String, Object> page(boolean hasMore, long nextCursor, List<Map<String, Object>> models) {
        return page(hasMore, nextCursor, models, null);
    }

    private Map<String, Object> page(boolean hasMore, long nextCursor, List<Map<String, Object>> models, String payload) {
        Map<String, Object> body = new HashMap<>();
        body.put("hasMore", hasMore);
        body.put("nextCursor", nextCursor);
        body.put("userMessageModels", models);
        if (payload != null) {
            body.put("payload", payload);
        }
        return Map.of("body", body);
    }

    private Map<String, Object> model(String id) {
        return Map.of("message", Map.of("messageId", id));
    }

    private static final class StubHistoryClient extends XianyuWebSocketClient {

        private final LongFunction<Map<String, Object>> pages;
        private int requestCount;

        private StubHistoryClient(LongFunction<Map<String, Object>> pages, ExecutorService executor) {
            super(URI.create("ws://localhost"), Map.of(), "1", null, null, executor);
            this.pages = pages;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        protected Map<String, Object> requestConversationHistoryPage(String cid, long cursor) {
            requestCount++;
            return pages.apply(cursor);
        }
    }
}
