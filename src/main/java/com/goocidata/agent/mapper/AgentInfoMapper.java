package com.goocidata.agent.mapper;

import com.goocidata.agent.entity.AgentInfo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 智能体 Mapper
 *
 * @author nzj
 */
@Mapper
public interface AgentInfoMapper {

    @Select("<script>"
            + "SELECT * FROM agent_info "
            + "<where>"
            + "  <if test='onlyEnabled'>status = 1</if>"
            + "  <if test='kw != null and kw != \"\"'> AND (name LIKE '%' || #{kw} || '%' OR description LIKE '%' || #{kw} || '%')</if>"
            + "</where>"
            + "ORDER BY created_at DESC"
            + "</script>")
    List<AgentInfo> list(@Param("kw") String kw, @Param("onlyEnabled") boolean onlyEnabled);

    @Select("SELECT * FROM agent_info WHERE id = #{id}")
    AgentInfo findById(@Param("id") String id);

    @Select("SELECT COUNT(1) FROM agent_info")
    long count();

    @Insert("INSERT INTO agent_info(id, name, avatar, description, system_prompt, llm_config_id, kb_ids, "
            + "welcome_msg, temperature, top_k, status, created_at, updated_at, "
            + "tool_mode, work_dir, allow_cmds, deny_cmds, tool_timeout, max_steps) VALUES("
            + "#{id}, #{name}, #{avatar}, #{description}, #{systemPrompt}, #{llmConfigId}, #{kbIds}, "
            + "#{welcomeMsg}, #{temperature}, #{topK}, #{status}, #{createdAt}, #{updatedAt}, "
            + "#{toolMode}, #{workDir}, #{allowCmds}, #{denyCmds}, #{toolTimeout}, #{maxSteps})")
    int insert(AgentInfo agent);

    @Update("UPDATE agent_info SET name = #{name}, avatar = #{avatar}, description = #{description}, "
            + "system_prompt = #{systemPrompt}, llm_config_id = #{llmConfigId}, kb_ids = #{kbIds}, "
            + "welcome_msg = #{welcomeMsg}, temperature = #{temperature}, top_k = #{topK}, status = #{status}, "
            + "tool_mode = #{toolMode}, work_dir = #{workDir}, allow_cmds = #{allowCmds}, "
            + "deny_cmds = #{denyCmds}, tool_timeout = #{toolTimeout}, max_steps = #{maxSteps}, "
            + "updated_at = #{updatedAt} WHERE id = #{id}")
    int update(AgentInfo agent);

    @Delete("DELETE FROM agent_info WHERE id = #{id}")
    int delete(@Param("id") String id);
}
