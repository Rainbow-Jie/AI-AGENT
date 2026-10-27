package com.goocidata.agent.entity;

import lombok.Data;

/**
 * 会话消息
 *
 * @author nzj
 */
@Data
public class ChatMessage {

    private String id;
    private String sessionId;

    /** user / assistant */
    private String role;

    private String content;

    /** 引用来源 JSON 数组 */
    private String refs;

    /** 工具调用记录 JSON 数组（回看会话时用于展开当时的操作轨迹） */
    private String toolLog;

    /** 本条回答消耗的 token 用量 JSON（含缓存命中明细） */
    private String usage;

    private String createdAt;
}
