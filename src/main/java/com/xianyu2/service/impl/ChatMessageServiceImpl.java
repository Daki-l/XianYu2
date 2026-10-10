package com.xianyu2.service.impl;

import com.xianyu2.common.ResultObject;
import com.xianyu2.entity.XianyuAccount;
import com.xianyu2.entity.XianyuChatMessage;
import com.xianyu2.entity.XianyuGoodsAutoReplyRecord;
import com.xianyu2.entity.XianyuHumanInterventionRecord;
import com.xianyu2.mapper.XianyuAccountMapper;
import com.xianyu2.mapper.XianyuChatMessageMapper;
import com.xianyu2.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.xianyu2.mapper.XianyuHumanInterventionRecordMapper;
import com.xianyu2.controller.dto.EndHumanTakeoverReqDTO;
import com.xianyu2.controller.dto.MsgContextReqDTO;
import com.xianyu2.controller.dto.MsgDTO;
import com.xianyu2.controller.dto.MsgListReqDTO;
import com.xianyu2.controller.dto.MsgListRespDTO;
import com.xianyu2.event.chatMessageEvent.ChatMessageData;
import com.xianyu2.event.chatMessageEvent.ChatMessageReceivedEvent;
import com.xianyu2.event.chatMessageEvent.ChatMessageEventSource;
import com.xianyu2.service.ChatMessageService;
import com.xianyu2.service.ChatMessagePersistenceService;
import com.xianyu2.service.PlatformHistorySyncCoordinator;
import com.xianyu2.service.PlatformHistoryMessageParser;
import com.xianyu2.service.WebSocketService;
import com.xianyu2.service.reply.HumanTakeoverManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 聊天消息服务实现
 * 
 * <p>职责：提供消息查询相关的服务</p>
 * <p>注意：WebSocket 消息的解析和保存现在由 SyncMessageHandler 直接处理</p>
 */
@Slf4j
@Service
public class ChatMessageServiceImpl implements ChatMessageService {

    private static final long HUMAN_TAKEOVER_TIMELINE_ID_BASE = -1_000_000_000_000L;

    private static final Comparator<MsgDTO> TIMELINE_ASC = Comparator
            .comparing(MsgDTO::getMessageTime, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(MsgDTO::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    
    @Autowired
    private XianyuChatMessageMapper chatMessageMapper;

    @Autowired
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;

    @Autowired
    private XianyuHumanInterventionRecordMapper interventionRecordMapper;

    @Autowired
    private HumanTakeoverManager takeoverManager;

    @Autowired
    private ChatMessagePersistenceService chatMessagePersistenceService;
    
    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private WebSocketService webSocketService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformHistorySyncCoordinator platformHistorySyncCoordinator;
    
    @Override
    public List<XianyuChatMessage> getMessagesByAccountId(Long accountId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return chatMessageMapper.findByAccountId(accountId, pageSize, offset);
    }
    
    @Override
    public List<XianyuChatMessage> getMessagesBySessionId(String sessionId) {
        return chatMessageMapper.findBySId(sessionId);
    }
    
    @Override
    public ResultObject<MsgListRespDTO> getMessageList(MsgListReqDTO reqDTO) {
        try {
            // 参数验证
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.validateFailed("xianyuAccountId不能为空");
            }
            
            // 设置默认值
            int pageNum = reqDTO.getPageNum() != null && reqDTO.getPageNum() > 0 ? reqDTO.getPageNum() : 1;
            int pageSize = reqDTO.getPageSize() != null && reqDTO.getPageSize() > 0 ? reqDTO.getPageSize() : 20;
            
            // 计算偏移量
            int offset = (pageNum - 1) * pageSize;
            
            // 获取当前账号的UNB（用于过滤）
            String currentAccountUnb = null;
            if (reqDTO.getFilterCurrentAccount() != null && reqDTO.getFilterCurrentAccount()) {
                XianyuAccount account = accountMapper.selectById(reqDTO.getXianyuAccountId());
                if (account != null) {
                    currentAccountUnb = account.getUnb();
                }
            }
            
            // 查询总数
            int totalCount = chatMessageMapper.countMessages(
                    reqDTO.getXianyuAccountId(),
                    reqDTO.getXyGoodsId(),
                    currentAccountUnb
            );
            
            // 查询分页数据
            List<XianyuChatMessage> messages = chatMessageMapper.findMessagesByPage(
                    reqDTO.getXianyuAccountId(),
                    reqDTO.getXyGoodsId(),
                    currentAccountUnb,
                    pageSize,
                    offset
            );
            
            // 转换为DTO
            List<MsgDTO> msgDTOList = new ArrayList<>();
            if (messages != null) {
                for (XianyuChatMessage message : messages) {
                    msgDTOList.add(toMessageDto(message));
                }
            }
            
            // 计算总页数
            int totalPage = (int) Math.ceil((double) totalCount / pageSize);
            if (totalPage == 0 && totalCount > 0) {
                totalPage = 1;
            }
            
            // 构建响应
            MsgListRespDTO respDTO = new MsgListRespDTO();
            respDTO.setList(msgDTOList);
            respDTO.setTotalCount(totalCount);
            respDTO.setPageNum(pageNum);
            respDTO.setPageSize(pageSize);
            respDTO.setTotalPage(totalPage);
            
            return ResultObject.success(respDTO);
            
        } catch (Exception e) {
            log.error("查询消息列表失败: accountId={}, xyGoodsId={}, filterCurrentAccount={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getXyGoodsId(), reqDTO.getFilterCurrentAccount(), e);
            return ResultObject.failed("查询消息列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public ResultObject<?> getContextMessages(MsgContextReqDTO reqDTO) {
        try {
            if (reqDTO.getXianyuAccountId() == null || reqDTO.getSid() == null || reqDTO.getSid().isEmpty()) {
                return ResultObject.validateFailed("xianyuAccountId和sid不能为空");
            }
            if (accountMapper.selectById(reqDTO.getXianyuAccountId()) == null) {
                return ResultObject.validateFailed("账号不存在或无权访问");
            }

            chatMessagePersistenceService.reconcileSession(reqDTO.getXianyuAccountId(), reqDTO.getSid());
            
            int limit = reqDTO.getLimit() != null && reqDTO.getLimit() > 0
                    ? Math.min(reqDTO.getLimit(), 500) : 20;
            int offset = reqDTO.getOffset() != null && reqDTO.getOffset() >= 0 ? reqDTO.getOffset() : 0;
            
            List<XianyuChatMessage> messages = chatMessageMapper.findRecentBySId(
                    reqDTO.getXianyuAccountId(), reqDTO.getSid(), limit, offset);
            
            List<MsgDTO> msgDTOList = new ArrayList<>();
            if (messages != null) {
                for (XianyuChatMessage message : messages) {
                    msgDTOList.add(toMessageDto(message));
                }
            }

            if (offset == 0) {
                for (XianyuGoodsAutoReplyRecord record : autoReplyRecordMapper.findTimelineStates(
                        reqDTO.getXianyuAccountId(), reqDTO.getSid())) {
                    msgDTOList.add(toAutoReplyStatusDto(record));
                }
                XianyuHumanInterventionRecord takeoverRecord = interventionRecordMapper.findActiveByAccountAndSId(
                        reqDTO.getXianyuAccountId(), reqDTO.getSid());
                if (takeoverRecord != null) {
                    msgDTOList.add(toHumanTakeoverStatusDto(takeoverRecord));
                }
            }

            // The context API always returns a chronological timeline. Paging still selects from newest to oldest.
            msgDTOList.sort(TIMELINE_ASC);
            
            return ResultObject.success(msgDTOList);
            
        } catch (Exception e) {
            log.error("查询上下文消息失败: sid={}", reqDTO.getSid(), e);
            return ResultObject.failed("查询上下文消息失败: " + e.getMessage());
        }
    }

    @Override
    public ResultObject<?> endHumanTakeover(EndHumanTakeoverReqDTO reqDTO) {
        if (reqDTO == null || reqDTO.getXianyuAccountId() == null || reqDTO.getSid() == null || reqDTO.getSid().isBlank()) {
            return ResultObject.validateFailed("xianyuAccountId和sid不能为空");
        }
        if (accountMapper.selectById(reqDTO.getXianyuAccountId()) == null) {
            return ResultObject.validateFailed("账号不存在或无权访问");
        }

        boolean changed = takeoverManager.endTakeover(reqDTO.getXianyuAccountId(), reqDTO.getSid());
        return ResultObject.success(Map.of(
                "ended", true,
                "changed", changed,
                "message", "人工接管已强制结束"));
    }

    @Override
    public ResultObject<?> syncContextMessages(MsgContextReqDTO reqDTO) {
        if (reqDTO.getXianyuAccountId() == null || reqDTO.getSid() == null || reqDTO.getSid().isBlank()) {
            return ResultObject.validateFailed("xianyuAccountId和sid不能为空");
        }
        XianyuAccount account = accountMapper.selectById(reqDTO.getXianyuAccountId());
        if (account == null) {
            return ResultObject.validateFailed("账号不存在或无权访问");
        }
        int maxMessages = reqDTO.getMaxMessages() == null ? 500
                : Math.max(20, Math.min(reqDTO.getMaxMessages(), 500));
        return platformHistorySyncCoordinator.execute(reqDTO.getXianyuAccountId(), reqDTO.getSid(),
                () -> syncContextMessages(reqDTO.getXianyuAccountId(), account, reqDTO.getSid(), maxMessages));
    }

    private ResultObject<?> syncContextMessages(Long accountId, XianyuAccount account, String sid, int maxMessages) {
        List<java.util.Map<String, Object>> history = webSocketService.listConversationHistory(
                accountId, sid, maxMessages);
        List<XianyuChatMessage> messages = new PlatformHistoryMessageParser(objectMapper).parse(
                accountId, sid, history);
        int inserted = 0;
        String ownUserId = account.getUnb();
        for (XianyuChatMessage message : messages) {
            if (chatMessagePersistenceService.savePlatformHistory(message, ownUserId) == 1) {
                publishHistoryMessageReceivedEvent(message);
                inserted++;
            }
        }
        log.info("【账号{}】会话历史同步完成: sid={}, received={}, parsed={}, newlyInserted={}",
                accountId, sid, history.size(), messages.size(), inserted);
        return ResultObject.success(java.util.Map.of("received", history.size(), "saved", inserted));
    }

    private void publishHistoryMessageReceivedEvent(XianyuChatMessage message) {
        ChatMessageData messageData = new ChatMessageData();
        BeanUtils.copyProperties(message, messageData);
        eventPublisher.publishEvent(new ChatMessageReceivedEvent(
                this, messageData, ChatMessageEventSource.PLATFORM_HISTORY));
        log.info("【账号{}】历史消息首次入库并发布接收事件: pnmId={}, sid={}",
                message.getXianyuAccountId(), message.getPnmId(), message.getSId());
    }

    private MsgDTO toMessageDto(XianyuChatMessage message) {
        MsgDTO msgDTO = new MsgDTO();
        msgDTO.setId(message.getId());
        msgDTO.setSId(message.getSId());
        msgDTO.setContentType(message.getContentType());
        msgDTO.setMsgContent(message.getMsgContent());
        msgDTO.setXyGoodsId(message.getXyGoodsId());
        msgDTO.setReminderUrl(message.getReminderUrl());
        msgDTO.setSenderUserName(message.getSenderUserName());
        msgDTO.setSenderUserId(message.getSenderUserId());
        msgDTO.setMessageTime(message.getMessageTime());
        msgDTO.setMessageSource(message.getMessageSource());
        msgDTO.setReplyOrigin(message.getReplyOrigin());
        msgDTO.setTimelineType("MESSAGE");
        return msgDTO;
    }

    private MsgDTO toAutoReplyStatusDto(XianyuGoodsAutoReplyRecord record) {
        MsgDTO status = new MsgDTO();
        status.setId(-record.getId());
        status.setSId(record.getSId());
        status.setXyGoodsId(record.getXyGoodsId());
        status.setAutoReplyRecordId(record.getId());
        status.setScheduledTime(record.getScheduledTime());
        status.setTimelineType(switch (record.getState()) {
            case 0 -> "AI_PENDING";
            case 2 -> "AI_PROCESSING";
            case -1 -> "AI_FAILED";
            case -2 -> "AI_CANCELLED";
            default -> "MESSAGE";
        });
        LocalDateTime timelineTime = record.getState() != null && record.getState() == 0
                ? record.getScheduledTime() : record.getStatusTime();
        status.setMessageTime(toEpochMillis(timelineTime));
        if (record.getState() != null && record.getState() == -1) {
            status.setStatusReason(safeStatusReason(record.getLastErrorCode()));
        } else if (record.getState() != null && record.getState() == -2) {
            status.setStatusReason("SERVICE_RESTART".equals(record.getLastErrorCode())
                    ? "服务重启，AI回复已取消" : null);
        }
        return status;
    }

    private MsgDTO toHumanTakeoverStatusDto(XianyuHumanInterventionRecord record) {
        MsgDTO status = new MsgDTO();
        long recordId = record.getId() == null ? 0L : record.getId();
        status.setId(HUMAN_TAKEOVER_TIMELINE_ID_BASE - recordId);
        status.setSId(record.getSId());
        status.setXyGoodsId(record.getXyGoodsId());
        status.setTimelineType("HUMAN_TAKEOVER");
        status.setMessageTime(toEpochMillis(record.getCreatedTime()));
        status.setTakeoverEndTime(record.getEndTime());
        status.setStatusReason("商家手动回复后，AI 自动回复已暂停");
        return status;
    }

    private Long toEpochMillis(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String safeStatusReason(String errorCode) {
        if ("AI_TIMEOUT".equals(errorCode)) {
            return "AI 请求超时";
        }
        if ("AI_NOT_AVAILABLE".equals(errorCode)) {
            return "AI 服务未配置或不可用";
        }
        if ("AI_EMPTY_REPLY".equals(errorCode)) {
            return "AI 未返回有效回复";
        }
        if ("SEND_RESULT_UNKNOWN".equals(errorCode)) {
            return "闲鱼发送结果未确认，请人工核对";
        }
        return "自动回复失败";
    }
}
