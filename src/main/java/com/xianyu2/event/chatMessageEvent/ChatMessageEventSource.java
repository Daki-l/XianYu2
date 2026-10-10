package com.xianyu2.event.chatMessageEvent;

/**
 * 标识聊天消息进入事件链路的来源。
 *
 * <p>只有可确认的实时消息可以触发对外自动化；回放和历史消息仍会落库、展示，
 * 但不应产生新的业务副作用。</p>
 */
public enum ChatMessageEventSource {

    /** 当前 WebSocket 连接建立后产生的实时消息。 */
    REALTIME,

    /** WebSocket 重连补偿阶段回放的消息。 */
    WEBSOCKET_CATCHUP,

    /** 用户显式同步平台完整历史返回的消息。 */
    PLATFORM_HISTORY,

    /** 无法确认到达时机，默认禁止副作用。 */
    UNKNOWN;

    public boolean permitsRealtimeSideEffects() {
        return this == REALTIME;
    }
}
