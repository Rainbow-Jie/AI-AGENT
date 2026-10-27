package com.goocidata.agent.mapper;

import com.goocidata.agent.entity.KbDocument;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 知识库文档 Mapper
 *
 * @author nzj
 */
@Mapper
public interface KbDocumentMapper {

    @Select("SELECT * FROM kb_document WHERE kb_id = #{kbId} ORDER BY created_at DESC")
    List<KbDocument> listByKb(@Param("kbId") String kbId);

    @Select("SELECT * FROM kb_document WHERE id = #{id}")
    KbDocument findById(@Param("id") String id);

    @Select("SELECT COUNT(1) FROM kb_document")
    long count();

    @Insert("INSERT INTO kb_document(id, kb_id, name, file_type, file_size, char_count, chunk_count, "
            + "status, error_msg, created_at) VALUES(#{id}, #{kbId}, #{name}, #{fileType}, #{fileSize}, "
            + "#{charCount}, #{chunkCount}, #{status}, #{errorMsg}, #{createdAt})")
    int insert(KbDocument doc);

    @Update("UPDATE kb_document SET chunk_count = #{chunkCount}, char_count = #{charCount} WHERE id = #{id}")
    int updateStat(@Param("id") String id, @Param("chunkCount") int chunkCount, @Param("charCount") int charCount);

    @Delete("DELETE FROM kb_document WHERE id = #{id}")
    int delete(@Param("id") String id);

    @Delete("DELETE FROM kb_document WHERE kb_id = #{kbId}")
    int deleteByKb(@Param("kbId") String kbId);
}
