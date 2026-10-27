package com.goocidata.agent.mapper;

import com.goocidata.agent.entity.LlmConfig;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 模型配置 Mapper
 *
 * @author nzj
 */
@Mapper
public interface LlmConfigMapper {

    @Select("SELECT * FROM llm_config ORDER BY is_default DESC, created_at DESC")
    List<LlmConfig> listAll();

    @Select("SELECT * FROM llm_config WHERE id = #{id}")
    LlmConfig findById(@Param("id") String id);

    @Select("SELECT * FROM llm_config WHERE status = 1 ORDER BY is_default DESC, created_at DESC LIMIT 1")
    LlmConfig findDefault();

    @Select("SELECT COUNT(1) FROM llm_config")
    long count();

    @Insert("INSERT INTO llm_config(id, name, provider, base_url, api_key, model, temperature, max_tokens, "
            + "is_default, status, remark, created_at, updated_at) VALUES("
            + "#{id}, #{name}, #{provider}, #{baseUrl}, #{apiKey}, #{model}, #{temperature}, #{maxTokens}, "
            + "#{isDefault}, #{status}, #{remark}, #{createdAt}, #{updatedAt})")
    int insert(LlmConfig config);

    @Update("UPDATE llm_config SET name = #{name}, provider = #{provider}, base_url = #{baseUrl}, "
            + "api_key = #{apiKey}, model = #{model}, temperature = #{temperature}, max_tokens = #{maxTokens}, "
            + "is_default = #{isDefault}, status = #{status}, remark = #{remark}, updated_at = #{updatedAt} "
            + "WHERE id = #{id}")
    int update(LlmConfig config);

    @Update("UPDATE llm_config SET is_default = 0")
    int clearDefault();

    @Delete("DELETE FROM llm_config WHERE id = #{id}")
    int delete(@Param("id") String id);
}
