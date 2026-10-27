package com.goocidata.agent.entity;

import lombok.Data;

/**
 * 知识库文档
 *
 * @author nzj
 */
@Data
public class KbDocument {

    private String id;
    private String kbId;
    private String name;
    private String fileType;
    private Long fileSize;
    private Integer charCount;
    private Integer chunkCount;

    /** READY / FAILED */
    private String status;

    private String errorMsg;
    private String createdAt;
}
