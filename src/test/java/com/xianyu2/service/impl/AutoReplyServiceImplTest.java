package com.xianyu2.service.impl;

import com.xianyu2.entity.XianyuGoodsConfig;
import com.xianyu2.entity.XianyuGoodsInfo;
import com.xianyu2.event.chatMessageEvent.ChatMessageData;
import com.xianyu2.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.xianyu2.mapper.XianyuGoodsConfigMapper;
import com.xianyu2.mapper.XianyuGoodsInfoMapper;
import com.xianyu2.service.SentMessageSaveService;
import com.xianyu2.service.WebSocketService;
import com.xianyu2.service.reply.ReplyStrategy;
import com.xianyu2.service.reply.ReplyStrategyResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoReplyServiceImplTest {

    @Mock
    private XianyuGoodsConfigMapper goodsConfigMapper;
    @Mock
    private XianyuGoodsInfoMapper goodsInfoMapper;
    @Mock
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;
    @Mock
    private WebSocketService webSocketService;
    @Mock
    private SentMessageSaveService sentMessageSaveService;
    @Mock
    private ReplyStrategyResolver replyStrategyResolver;
    @Mock
    private ReplyStrategy replyStrategy;

    @InjectMocks
    private AutoReplyServiceImpl autoReplyService;

    @Test
    void aiFailureIsRecordedWithoutSendingOrPersistingAnAiMessage() {
        XianyuGoodsConfig config = new XianyuGoodsConfig();
        config.setXianyuAutoReplyOn(1);
        when(goodsConfigMapper.selectByAccountAndGoodsId(1L, "goods-1")).thenReturn(config);

        XianyuGoodsInfo goodsInfo = new XianyuGoodsInfo();
        goodsInfo.setId(11L);
        when(goodsInfoMapper.selectOne(any())).thenReturn(goodsInfo);
        when(replyStrategyResolver.resolve(any())).thenReturn(replyStrategy);
        when(replyStrategy.execute(any())).thenReturn(
                ReplyStrategy.ReplyResult.fail("AI_TIMEOUT", "AI 请求超时"));

        autoReplyService.executeAutoReply(List.of(message()), 99L);

        verify(autoReplyRecordMapper).failActive(99L, "AI_TIMEOUT", "AI 请求超时");
        verify(webSocketService, never()).sendMessage(any(), any(), any(), any());
        verify(webSocketService, never()).sendMessageWithResult(any(), any(), any(), any());
        verify(webSocketService, never()).sendImageMessageWithResult(any(), any(), any(), any(), anyInt(), anyInt());
        verify(sentMessageSaveService, never()).saveAiAssistantReply(any(), any(), any(), any(), any());
        verify(sentMessageSaveService, never()).saveAiImageReply(any(), any(), any(), any(), any());
    }

    private ChatMessageData message() {
        ChatMessageData message = new ChatMessageData();
        message.setXianyuAccountId(1L);
        message.setXyGoodsId("goods-1");
        message.setSId("buyer@goofish");
        message.setPnmId("pnm-1");
        message.setSenderUserId("buyer");
        message.setSenderUserName("buyer");
        message.setMsgContent("hello");
        return message;
    }
}
