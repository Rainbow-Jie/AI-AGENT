package com.goocidata.agent.mapper;

import com.goocidata.agent.entity.ChatSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 会话 Mapper
 *
 * @author nzj
 */
@Mapper
public interface ChatSessionMapper {

    @Select("SELECT * FROM chat_session WHERE user_id = #{userId} ORDER BY updated_at DESC LIMIT 100")
    List<ChatSession> listByUser(@Param("userId") String userId);

    @Select("SELECT * FROM chat_session WHERE id = #{id}")
    ChatSession findById(@Param("id") String id);

    @Insert("INSERT INTO chat_session(id, agent_id, agent_name, user_id, title, created_at, updated_at) "
            + "VALUES(#{id}, #{agentId}, #{agentName}, #{userId}, #{title}, #{createdAt}, #{updatedAt})")
    int insert(ChatSession session);

    @Update("UPDATE chat_session SET title = #{title}, updated_at = #{updatedAt} WHERE id = #{id}")
    int updateTitle(@Param("id") String id, @Param("title") String title, @Param("updatedAt") String updatedAt);

    @Update("UPDATE chat_session SET updated_at = #{updatedAt} WHERE id = #{id}")
    int touch(@Param("id") String id, @Param("updatedAt") String updatedAt);

    @Delete("DELETE FROM chat_session WHERE id = #{id}")
    int delete(@Param("id") String id);

    @Select("SELECT COUNT(1) FROM chat_session")
    long count();
}
