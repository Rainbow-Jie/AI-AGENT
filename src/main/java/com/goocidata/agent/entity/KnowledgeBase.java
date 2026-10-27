package com.goocidata.agent.entity;

import lombok.Data;

/**
 * 知识库
 *
 * @author nzj
 */
@Data
public class KnowledgeBase {

    private String id;
    private String name;
    private String description;
    private Integer docCount;
    private Integer chunkCount;
    private String createdAt;
    private String updatedAt;
}
