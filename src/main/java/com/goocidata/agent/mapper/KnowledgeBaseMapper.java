package com.goocidata.agent.mapper;

import com.goocidata.agent.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 知识库 Mapper
 *
 * @author nzj
 */
@Mapper
public interface KnowledgeBaseMapper {

    @Select("SELECT * FROM knowledge_base ORDER BY created_at DESC")
    List<KnowledgeBase> listAll();

    @Select("SELECT * FROM knowledge_base WHERE id = #{id}")
    KnowledgeBase findById(@Param("id") String id);

    @Select("SELECT COUNT(1) FROM knowledge_base")
    long count();

    @Insert("INSERT INTO knowledge_base(id, name, description, doc_count, chunk_count, created_at, updated_at) "
            + "VALUES(#{id}, #{name}, #{description}, #{docCount}, #{chunkCount}, #{createdAt}, #{updatedAt})")
    int insert(KnowledgeBase kb);

    @Update("UPDATE knowledge_base SET name = #{name}, description = #{description}, updated_at = #{updatedAt} "
            + "WHERE id = #{id}")
    int update(KnowledgeBase kb);

    @Update("UPDATE knowledge_base SET doc_count = (SELECT COUNT(1) FROM kb_document WHERE kb_id = #{id}), "
            + "chunk_count = (SELECT COUNT(1) FROM kb_chunk WHERE kb_id = #{id}), updated_at = #{now} WHERE id = #{id}")
    int refreshStat(@Param("id") String id, @Param("now") String now);

    @Delete("DELETE FROM knowledge_base WHERE id = #{id}")
    int delete(@Param("id") String id);
}
