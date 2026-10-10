package com.xianyu2.event.chatMessageEvent.lister;

import com.xianyu2.entity.XianyuChatMessage;
import com.xianyu2.event.chatMessageEvent.ChatMessageData;
import com.xianyu2.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyu2.mapper.XianyuChatMessageMapper;
import com.xianyu2.service.ChatMessagePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会保存所有聊天消息
 * 
 * <p>监听 {@link ChatMessageReceivedEvent} 事件，负责将消息保存到数据库</p>
 * 
 * <p>特点：</p>
 * <ul>
 *   <li>消息先完成落库，再推进WebSocket同步游标</li>
 *   <li>自动去重，避免重复保存</li>
 *   <li>独立模块，与其他监听器互不影响</li>
 * </ul>
 * 
 * @since 1.0
 */
@Slf4j
@Component
public class ChatMessageEventSaveListener {
    
    @Autowired
    private XianyuChatMessageMapper chatMessageMapper;

    @Autowired
    private ChatMessagePersistenceService chatMessagePersistenceService;

    private final ConcurrentMap<String, Object> persistenceLocks = new ConcurrentHashMap<>();
    
    /**
     * 处理聊天消息接收事件 - 保存消息到数据库
     * 
     * @param event 聊天消息接收事件
     */
    @Order(0)
    @EventListener
    public void handleChatMessageReceived(ChatMessageReceivedEvent event) {
        ChatMessageData messageData = event.getMessageData();
        if (messageData.getXianyuAccountId() == null || messageData.getPnmId() == null
                || messageData.getPnmId().isBlank()) {
            event.markPersistenceResult(false, null);
            log.warn("[SaveListener]消息缺少账号或平台消息ID，禁止触发后续副作用");
            return;
        }

        String lockKey = messageData.getXianyuAccountId() + ":" + messageData.getPnmId();
        Object lock = persistenceLocks.computeIfAbsent(lockKey, ignored -> new Object());
        try {
            synchronized (lock) {
                persistAndMark(event, messageData);
            }
        } catch (Exception e) {
            event.markPersistenceResult(false, null);
            log.error("【账号{}】[SaveListener]保存消息异常: pnmId={}",
                    messageData.getXianyuAccountId(), messageData.getPnmId(), e);
        } finally {
            persistenceLocks.remove(lockKey, lock);
        }
    }

    private void persistAndMark(ChatMessageReceivedEvent event, ChatMessageData messageData) {
        XianyuChatMessage existing = chatMessageMapper.findByPnmId(
                messageData.getXianyuAccountId(), messageData.getPnmId());
        if (existing != null) {
            event.markPersistenceResult(false, existing);
            log.info("【账号{}】[SaveListener]消息已存在，禁止重复副作用: pnmId={}",
                    messageData.getXianyuAccountId(), messageData.getPnmId());
            return;
        }

        XianyuChatMessage message = new XianyuChatMessage();
        org.springframework.beans.BeanUtils.copyProperties(messageData, message);
        int result = chatMessagePersistenceService.save(message);
        XianyuChatMessage persisted = chatMessageMapper.findByPnmId(
                message.getXianyuAccountId(), message.getPnmId());
        event.markPersistenceResult(result > 0, persisted == null ? message : persisted);

        if (result > 0) {
            log.info("【账号{}】[SaveListener]消息首次保存成功: pnmId={}, id={}",
                    message.getXianyuAccountId(), message.getPnmId(),
                    persisted == null ? message.getId() : persisted.getId());
        } else {
            log.info("【账号{}】[SaveListener]消息未首次保存，禁止触发后续副作用: pnmId={}",
                    message.getXianyuAccountId(), message.getPnmId());
        }
    }
}
