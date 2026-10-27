package com.goocidata.agent.dto;

import lombok.Data;

/**
 * 检索命中的知识片段
 *
 * @author nzj
 */
@Data
public class ChunkHit {

    private String chunkId;
    private String docId;
    private String docName;
    private Integer chunkIndex;
    private String content;
    private Double score;

    /** 送给前端展示时的截断预览 */
    private String preview;
}
