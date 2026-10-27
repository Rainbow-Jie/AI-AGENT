package com.goocidata.agent.entity;

import lombok.Data;

/**
 * 知识库切片
 *
 * @author nzj
 */
@Data
public class KbChunk {

    private String id;
    private String kbId;
    private String docId;
    private String docName;
    private Integer chunkIndex;
    private String content;
    private Integer charLen;
    private String createdAt;
}
