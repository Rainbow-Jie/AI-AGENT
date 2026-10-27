package com.goocidata.agent.mapper;

import com.goocidata.agent.entity.KbChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识库切片 Mapper
 *
 * @author nzj
 */
@Mapper
public interface KbChunkMapper {

    @Insert("<script>"
            + "INSERT INTO kb_chunk(id, kb_id, doc_id, doc_name, chunk_index, content, char_len, created_at) VALUES "
            + "<foreach collection='list' item='it' separator=','>"
            + "(#{it.id}, #{it.kbId}, #{it.docId}, #{it.docName}, #{it.chunkIndex}, #{it.content}, #{it.charLen}, #{it.createdAt})"
            + "</foreach>"
            + "</script>")
    int batchInsert(@Param("list") List<KbChunk> chunks);

    @Select("<script>"
            + "SELECT * FROM kb_chunk WHERE kb_id IN "
            + "<foreach collection='kbIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " ORDER BY kb_id, chunk_index"
            + "</script>")
    List<KbChunk> listByKbIds(@Param("kbIds") List<String> kbIds);

    @Select("SELECT * FROM kb_chunk WHERE doc_id = #{docId} ORDER BY chunk_index")
    List<KbChunk> listByDoc(@Param("docId") String docId);

    @Select("SELECT * FROM kb_chunk WHERE kb_id = #{kbId} ORDER BY doc_id, chunk_index LIMIT #{limit} OFFSET #{offset}")
    List<KbChunk> pageByKb(@Param("kbId") String kbId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(1) FROM kb_chunk WHERE kb_id = #{kbId}")
    long countByKb(@Param("kbId") String kbId);

    @Select("SELECT COUNT(1) FROM kb_chunk")
    long countAll();

    @Delete("DELETE FROM kb_chunk WHERE doc_id = #{docId}")
    int deleteByDoc(@Param("docId") String docId);

    @Delete("DELETE FROM kb_chunk WHERE kb_id = #{kbId}")
    int deleteByKb(@Param("kbId") String kbId);
}
