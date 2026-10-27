package com.goocidata.agent.mapper;

import com.goocidata.agent.dto.UsageRow;
import com.goocidata.agent.entity.ChatMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 会话消息 Mapper
 *
 * @author nzj
 */
@Mapper
public interface ChatMessageMapper {

    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} ORDER BY created_at, id")
    List<ChatMessage> listBySession(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<ChatMessage> listRecent(@Param("sessionId") String sessionId, @Param("limit") int limit);

    @Insert("INSERT INTO chat_message(id, session_id, role, content, refs, tool_log, usage, created_at) "
            + "VALUES(#{id}, #{sessionId}, #{role}, #{content}, #{refs}, #{toolLog}, #{usage}, #{createdAt})")
    int insert(ChatMessage message);

    @Delete("DELETE FROM chat_message WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") String sessionId);

    @Select("SELECT COUNT(1) FROM chat_message")
    long count();

    /**
     * 全部消息的用量记录（JSON 文本），用于汇总 token 消耗。
     * 在 Java 侧解析聚合，避免依赖各家 SQLite 的 JSON 扩展。
     */
    @Select("SELECT usage FROM chat_message WHERE usage IS NOT NULL AND usage <> '' "
            + "ORDER BY created_at DESC LIMIT 5000")
    List<String> listUsages();

    /**
     * 带归属人的用量记录：消息本身没存用户，用户挂在会话上。
     * <p>
     * 会话被删时消息一并删除，所以这里不会有孤儿行；但用户被删后会话仍保留，
     * 这种行的 user_id 已经找不到对应账号，聚合时会被忽略。
     */
    @Select("SELECT s.user_id AS userId, m.usage AS usage "
            + "FROM chat_message m JOIN chat_session s ON s.id = m.session_id "
            + "WHERE m.usage IS NOT NULL AND m.usage <> '' "
            + "ORDER BY m.created_at DESC LIMIT 20000")
    List<UsageRow> listUsagesByUser();
}
