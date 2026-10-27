package com.goocidata.agent.dto;

import lombok.Data;

/**
 * 「某条消息的用量属于谁」——用量 JSON 挂在 chat_message 上，
 * 归属人要到 chat_session 里取。
 *
 * @author nzj
 */
@Data
public class UsageRow {

    private String userId;

    /** usage JSON 原文，形如 {"model":"deepseek-flash","promptTokens":...} */
    private String usage;
}
