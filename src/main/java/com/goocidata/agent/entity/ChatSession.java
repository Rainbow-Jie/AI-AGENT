package com.goocidata.agent.entity;

import lombok.Data;

/**
 * 会话
 *
 * @author nzj
 */
@Data
public class ChatSession {

    private String id;
    private String agentId;
    private String agentName;
    private String userId;
    private String title;
    private String createdAt;
    private String updatedAt;
}
