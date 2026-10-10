package com.xianyu2.event.chatMessageEvent;

import com.xianyu2.entity.XianyuChatMessage;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 聊天消息接收事件
 * 
 * <p>当WebSocket接收到消息并解析后触发此事件（消息尚未入库）</p>
 * 
 * <p>此事件采用一对多广播模式，多个监听器可以同时监听此事件：</p>
 * <ul>
 *   <li>ChatMessagePersistenceListener - 负责将消息异步保存到数据库</li>
 *   <li>AutoDeliveryTriggerListener - 负责判断是否需要触发自动发货</li>
 *   <li>其他业务监听器 - 可以根据需要添加更多监听器</li>
 * </ul>
 * 
 * <p>所有监听器使用@Async异步执行，互不阻塞</p>
 * 
 * @since 1.0
 */
@Getter
public class ChatMessageReceivedEvent extends ApplicationEvent {
    
    /**
     * 解析后的聊天消息数据对象
     * 
     * <p>注意：使用自定义对象而不是数据库实体，避免耦合</p>
     */
    private final ChatMessageData messageData;

    /**
     * 消息进入事件链路时的来源。
     */
    private final ChatMessageEventSource receiptSource;

    /**
     * 保存监听器是否已完成首次入库判定。
     */
    private volatile boolean persistenceCompleted;

    /**
     * 该事件对应的平台消息是否是本次首次入库。
     */
    private volatile boolean newlyPersisted;
    
    /**
     * 构造函数
     * 
     * @param source 事件源（通常是发布事件的Service）
     * @param messageData 解析后的聊天消息数据对象
     */
    public ChatMessageReceivedEvent(Object source, ChatMessageData messageData) {
        this(source, messageData, ChatMessageEventSource.UNKNOWN);
    }

    public ChatMessageReceivedEvent(Object source, ChatMessageData messageData,
                                    ChatMessageEventSource receiptSource) {
        super(source);
        this.messageData = messageData;
        this.receiptSource = receiptSource == null ? ChatMessageEventSource.UNKNOWN : receiptSource;
    }

    /**
     * 保存监听器会在有副作用的监听器运行前写入首次入库判定。
     */
    public void markPersistenceResult(boolean newlyPersisted, XianyuChatMessage persistedMessage) {
        this.persistenceCompleted = true;
        this.newlyPersisted = newlyPersisted;
        if (persistedMessage == null) {
            return;
        }
        messageData.setId(persistedMessage.getId());
        messageData.setMessageSource(persistedMessage.getMessageSource());
        messageData.setReplyOrigin(persistedMessage.getReplyOrigin());
    }

    /**
     * 重复、回放、历史同步及本地回复回显均不得再次触发自动化。
     */
    public boolean isEligibleForRealtimeSideEffects() {
        if (!persistenceCompleted || !newlyPersisted || !receiptSource.permitsRealtimeSideEffects()) {
            return false;
        }
        String replyOrigin = messageData.getReplyOrigin();
        return !"AI".equalsIgnoreCase(replyOrigin) && !"BACKEND".equalsIgnoreCase(replyOrigin);
    }
}
