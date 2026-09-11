package com.xianyu2.mapper;

import com.xianyu2.entity.XianyuChatMessage;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 闲鱼聊天消息Mapper
 */
@Mapper
public interface XianyuChatMessageMapper {
    
    /**
     * 插入聊天消息
     */
    @Insert("INSERT INTO xianyu_chat_message (" +
            "xianyu_account_id, lwp, pnm_id, s_id, " +
            "content_type, msg_content, " +
            "sender_user_name, sender_user_id, sender_app_v, sender_os_type, " +
            "reminder_url, xy_goods_id, complete_msg, message_time, message_source, dedupe_fingerprint, reply_origin" +
            ") VALUES (" +
            "#{xianyuAccountId}, #{lwp}, #{pnmId}, #{sId}, " +
            "#{contentType}, #{msgContent}, " +
            "#{senderUserName}, #{senderUserId}, #{senderAppV}, #{senderOsType}, " +
            "#{reminderUrl}, #{xyGoodsId}, #{completeMsg}, #{messageTime}, " +
            "COALESCE(#{messageSource}, 'PLATFORM'), #{dedupeFingerprint}, #{replyOrigin}" +
            ") ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(XianyuChatMessage message);

    /**
     * 历史同步使用平台原始消息回填可信字段，保留本地去重和回复来源元数据。
     */
    @Insert("INSERT INTO xianyu_chat_message (" +
            "xianyu_account_id, lwp, pnm_id, s_id, " +
            "content_type, msg_content, " +
            "sender_user_name, sender_user_id, sender_app_v, sender_os_type, " +
            "reminder_url, xy_goods_id, complete_msg, message_time, message_source, dedupe_fingerprint, reply_origin" +
            ") VALUES (" +
            "#{xianyuAccountId}, #{lwp}, #{pnmId}, #{sId}, " +
            "#{contentType}, #{msgContent}, " +
            "#{senderUserName}, #{senderUserId}, #{senderAppV}, #{senderOsType}, " +
            "#{reminderUrl}, #{xyGoodsId}, #{completeMsg}, #{messageTime}, " +
            "COALESCE(#{messageSource}, 'PLATFORM'), #{dedupeFingerprint}, #{replyOrigin}" +
            ") ON DUPLICATE KEY UPDATE " +
            "id = LAST_INSERT_ID(id), " +
            "content_type = IF(message_source = 'PLATFORM', VALUES(content_type), content_type), " +
            "msg_content = IF(message_source = 'PLATFORM', VALUES(msg_content), msg_content), " +
            "dedupe_fingerprint = IF(message_source = 'PLATFORM', VALUES(dedupe_fingerprint), dedupe_fingerprint), " +
            "sender_user_name = IF(message_source = 'PLATFORM', VALUES(sender_user_name), sender_user_name), " +
            "sender_user_id = IF(message_source = 'PLATFORM', VALUES(sender_user_id), sender_user_id), " +
            "sender_app_v = IF(message_source = 'PLATFORM', VALUES(sender_app_v), sender_app_v), " +
            "sender_os_type = IF(message_source = 'PLATFORM', VALUES(sender_os_type), sender_os_type), " +
            "reminder_url = IF(message_source = 'PLATFORM', VALUES(reminder_url), reminder_url), " +
            "xy_goods_id = IF(message_source = 'PLATFORM', VALUES(xy_goods_id), xy_goods_id), " +
            "complete_msg = IF(message_source = 'PLATFORM', VALUES(complete_msg), complete_msg), " +
            "message_time = IF(message_source = 'PLATFORM', VALUES(message_time), message_time)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsertPlatformHistory(XianyuChatMessage message);
    
    /**
     * 根据pnm_id查询（防止重复）
     */
    @Select("SELECT * FROM xianyu_chat_message " +
            "WHERE xianyu_account_id = #{accountId} AND pnm_id = #{pnmId}")
    XianyuChatMessage findByPnmId(@Param("accountId") Long accountId, 
                                  @Param("pnmId") String pnmId);
    
    /**
     * 查询账号的所有消息
     */
    @Select("SELECT * FROM xianyu_chat_message " +
            "WHERE xianyu_account_id = #{accountId} " +
            "AND duplicate_status = 0 " +
            "ORDER BY message_time DESC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<XianyuChatMessage> findByAccountId(@Param("accountId") Long accountId,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset);
    
    /**
     * 根据s_id查询会话的消息
     */
    @Select("SELECT * FROM xianyu_chat_message " +
            "WHERE s_id = #{sId} " +
            "AND duplicate_status = 0 " +
            "ORDER BY message_time ASC")
    List<XianyuChatMessage> findBySId(@Param("sId") String sId);
    
    /**
     * 根据发送者ID查询消息
     */
    @Select("SELECT * FROM xianyu_chat_message " +
            "WHERE sender_user_id = #{senderUserId} " +
            "AND duplicate_status = 0 " +
            "ORDER BY message_time DESC")
    List<XianyuChatMessage> findBySenderUserId(@Param("senderUserId") String senderUserId);

    /**
     * 同时查询买家来信和关联订单会话中的卖家发出消息。
     */
    @Select("SELECT * FROM xianyu_chat_message message WHERE message.xianyu_account_id = #{accountId} " +
            "AND message.duplicate_status = 0 " +
            "AND (message.sender_user_id = #{buyerUserId} OR message.s_id IN " +
            "(SELECT orders.sid FROM xianyu_goods_order orders WHERE orders.xianyu_account_id = #{accountId} " +
            "AND orders.buyer_user_id = #{buyerUserId} AND orders.sid IS NOT NULL)) " +
            "ORDER BY message.message_time DESC LIMIT 500")
    List<XianyuChatMessage> findByBuyerAndSessions(@Param("accountId") Long accountId,
                                                   @Param("buyerUserId") String buyerUserId);
    
    /**
     * 根据账号ID删除消息
     */
    @Delete("DELETE FROM xianyu_chat_message WHERE xianyu_account_id = #{accountId}")
    int deleteByAccountId(@Param("accountId") Long accountId);
    
    /**
     * 分页查询消息（支持按xy_goods_id过滤和sender_user_id过滤）
     *
     * @param accountId 账号ID（必选）
     * @param xyGoodsId 商品ID（可选，为null时不过滤）
     * @param senderUserId 发送者用户ID（可选，为null时不过滤）
     * @param limit 每页数量
     * @param offset 偏移量
     * @return 消息列表
     */
    @Select("<script>" +
            "SELECT * FROM xianyu_chat_message " +
            "WHERE xianyu_account_id = #{accountId} " +
            "AND duplicate_status = 0 " +
            "<if test='xyGoodsId != null and xyGoodsId != \"\"'>" +
            "AND xy_goods_id = #{xyGoodsId} " +
            "</if>" +
            "<if test='senderUserId != null and senderUserId != \"\"'>" +
            "AND sender_user_id != #{senderUserId} " +
            "</if>" +
            "ORDER BY message_time DESC " +
            "LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<XianyuChatMessage> findMessagesByPage(@Param("accountId") Long accountId,
                                               @Param("xyGoodsId") String xyGoodsId,
                                               @Param("senderUserId") String senderUserId,
                                               @Param("limit") int limit,
                                               @Param("offset") int offset);
    
    /**
     * 统计消息总数（支持按xy_goods_id过滤和sender_user_id过滤）
     *
     * @param accountId 账号ID（必选）
     * @param xyGoodsId 商品ID（可选，为null时不过滤）
     * @param senderUserId 发送者用户ID（可选，为null时不过滤）
     * @return 消息总数
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM xianyu_chat_message " +
            "WHERE xianyu_account_id = #{accountId} " +
            "AND duplicate_status = 0 " +
            "<if test='xyGoodsId != null and xyGoodsId != \"\"'>" +
            "AND xy_goods_id = #{xyGoodsId} " +
            "</if>" +
            "<if test='senderUserId != null and senderUserId != \"\"'>" +
            "AND sender_user_id != #{senderUserId} " +
            "</if>" +
            "</script>")
    int countMessages(@Param("accountId") Long accountId,
                     @Param("xyGoodsId") String xyGoodsId,
                     @Param("senderUserId") String senderUserId);
    
    /**
     * 根据会话ID查询最近N条消息（支持分页）
     *
     * @param sId 会话ID
     * @param limit 限制条数
     * @param offset 偏移量
     * @return 消息列表
     */
    @Select("SELECT * FROM xianyu_chat_message " +
            "WHERE xianyu_account_id = #{accountId} AND s_id = #{sId} AND duplicate_status = 0 " +
            "ORDER BY message_time DESC, id DESC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<XianyuChatMessage> findRecentBySId(@Param("accountId") Long accountId, @Param("sId") String sId,
                                            @Param("limit") int limit, @Param("offset") int offset);

    @Select("<script>" +
            "SELECT * FROM xianyu_chat_message " +
            "WHERE xianyu_account_id = #{accountId} " +
            "AND s_id = #{sid} " +
            "AND message_source = #{candidateSource} " +
            "AND content_type = #{candidateContentType} " +
            "AND duplicate_status = 0 " +
            "<if test='senderUserId != null and senderUserId != \"\"'>" +
            "AND sender_user_id = #{senderUserId} " +
            "</if>" +
            "ORDER BY message_time DESC" +
            "</script>")
    List<XianyuChatMessage> findCrossSourceCandidates(
            @Param("accountId") Long accountId,
            @Param("sid") String sid,
            @Param("senderUserId") String senderUserId,
            @Param("candidateSource") String candidateSource,
            @Param("candidateContentType") Integer candidateContentType);

    @Select("SELECT * FROM xianyu_chat_message " +
            "WHERE xianyu_account_id = #{accountId} AND s_id = #{sid} " +
            "AND duplicate_status = 0 " +
            "AND ((message_source = 'PLATFORM' AND content_type IN (1, 2)) " +
            "OR (message_source = 'LOCAL_AI' AND content_type IN (888, 887)) " +
            "OR (message_source = 'LOCAL' AND content_type IN (999, 997))) " +
            "ORDER BY message_time ASC, id ASC")
    List<XianyuChatMessage> findSessionCrossSourceMessages(@Param("accountId") Long accountId,
                                                            @Param("sid") String sid);

    @Update("UPDATE xianyu_chat_message SET duplicate_status = 1, duplicate_of_id = #{canonicalId} " +
            "WHERE id = #{duplicateId} AND duplicate_status = 0")
    int markDuplicate(@Param("duplicateId") Long duplicateId, @Param("canonicalId") Long canonicalId);

    @Update("UPDATE xianyu_chat_message SET reply_origin = #{replyOrigin} " +
            "WHERE id = #{messageId} AND content_type IN (1, 2)")
    int markReplyOrigin(@Param("messageId") Long messageId, @Param("replyOrigin") String replyOrigin);
}
