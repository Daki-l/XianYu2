import { request } from '@/utils/request';
import type { ApiResponse } from '@/types';

// 消息信息
export interface ChatMessage {
  id: number;
  xianyuAccountId: number;
  lwp: string;
  pnmId: string;
  sid: string;
  contentType: number;
  msgContent: string;
  senderUserName: string;
  senderUserId: string;
  senderAppV: string;
  senderOsType: string;
  reminderUrl: string;
  xyGoodsId: string;
  completeMsg: string;
  messageTime: string | number;
  createTime: string;
  messageSource?: string;
  replyOrigin?: string;
  timelineType?: 'MESSAGE' | 'AI_PENDING' | 'AI_PROCESSING' | 'AI_FAILED' | 'AI_CANCELLED' | 'HUMAN_TAKEOVER' | 'HUMAN_TAKEOVER_ENDED';
  autoReplyRecordId?: number;
  scheduledTime?: string;
  takeoverEndTime?: string;
  statusReason?: string;
  isNew?: boolean;
}

// 消息列表响应
export interface MessageListResponse {
  list: ChatMessage[];
  totalCount: number;
  totalPage: number;
  pageNum: number;
  pageSize: number;
}

export interface ConversationProfile {
  sid: string;
  avatar: string;
  nick: string;
}

// 获取消息列表
export function getMessageList(data: {
  xianyuAccountId: number;
  xyGoodsId?: string;
  pageNum?: number;
  pageSize?: number;
  filterCurrentAccount?: boolean; // 过滤当前账号消息
}, silent = false) {
  return request<MessageListResponse>({
    url: '/msg/list',
    method: 'POST',
    data,
    silent
  });
}

// 根据会话ID获取上下文消息
export function getContextMessages(data: {
  xianyuAccountId: number;
  sid: string;
  limit?: number;
  offset?: number;
}, options: { signal?: AbortSignal; silent?: boolean } = {}) {
  return request<ChatMessage[]>({
    url: '/msg/context',
    method: 'POST',
    data: {
      xianyuAccountId: data.xianyuAccountId,
      sid: data.sid,
      limit: data.limit || 20,
      offset: data.offset || 0
    },
    signal: options.signal,
    silent: options.silent
  });
}

export function syncContextMessages(data: {
  xianyuAccountId: number;
  sid: string;
  maxMessages?: number;
}, options: { signal?: AbortSignal; silent?: boolean } = {}) {
  return request<{ received: number; saved: number }>({
    url: '/msg/context/sync',
    method: 'POST',
    data,
    signal: options.signal,
    silent: options.silent
  });
}

export interface EndHumanTakeoverResponse {
  ended: boolean;
  changed: boolean;
  message: string;
}

export function endHumanTakeover(data: {
  xianyuAccountId: number;
  sid: string;
}) {
  return request<EndHumanTakeoverResponse>({
    url: '/msg/human-takeover/end',
    method: 'POST',
    data
  });
}

export function getConversationProfiles(data: {
  xianyuAccountId: number;
  sessionIds: string[];
}) {
  return request<ConversationProfile[]>({
    url: '/msg/conversation-profiles',
    method: 'POST',
    data
  });
}

// 发送消息
export function sendMessage(data: {
  xianyuAccountId: number;
  cid: string;
  toId: string;
  text: string;
  xyGoodsId?: string;
}) {
  return request<string>({
    url: '/websocket/sendMessage',
    method: 'POST',
    data
  });
}
