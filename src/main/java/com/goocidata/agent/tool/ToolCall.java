package com.goocidata.agent.tool;

import lombok.Data;

/**
 * 模型请求的工具调用
 *
 * @author nzj
 */
@Data
public class ToolCall {

    /** 模型生成的本轮唯一 ID，回灌结果时要用它对上 */
    private String id;

    private String name;

    /** 原始 JSON 字符串，可能不合法或缺字段，执行前统一解析校验 */
    private String arguments;
}
