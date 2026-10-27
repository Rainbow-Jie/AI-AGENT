package com.goocidata.agent.entity;

import lombok.Data;

/**
 * 智能体
 *
 * @author nzj
 */
@Data
public class AgentInfo {

    private String id;
    private String name;
    private String avatar;
    private String description;

    /** 角色设定 / 系统提示词 */
    private String systemPrompt;

    private String llmConfigId;

    /** 绑定的知识库 ID，逗号分隔 */
    private String kbIds;

    private String welcomeMsg;

    /** 为空则跟随模型配置 */
    private Double temperature;

    private Integer topK;
    private Integer status;
    private String createdAt;
    private String updatedAt;

    // ===== 工具权限 =====

    /** off 关闭 / read 只读 / write 读写 / full 全权（含执行命令） */
    private String toolMode;

    /** 沙箱根目录：所有文件读写与命令执行都被限制在这个目录内 */
    private String workDir;

    /** 命令白名单，逗号分隔；为空则走内置黑名单兜底 */
    private String allowCmds;

    /** 额外自定义黑名单片段，逗号分隔 */
    private String denyCmds;

    /** 单次命令超时（秒） */
    private Integer toolTimeout;

    /** 单次提问最多工具轮次 */
    private Integer maxSteps;

    /** 出参扩展：知识库名称，逗号分隔 */
    private String kbNames;

    /** 出参扩展：模型名称 */
    private String modelName;

    /** 出参扩展：实际生效的工具权限说明，前端直接展示 */
    private String toolScopeDesc;
}
