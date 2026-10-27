package com.goocidata.agent.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 大模型配置
 *
 * @author nzj
 */
@Data
public class LlmConfig {

    private String id;
    private String name;

    /** deepseek / openai / custom */
    private String provider;

    private String baseUrl;

    /** 密钥：仅接收写入，任何接口响应都不会带出原值 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;

    /** 出参用：脱敏后的密钥，仅读取不落库 */
    private String apiKeyMask;

    private String model;
    private Double temperature;
    private Integer maxTokens;
    private Integer isDefault;
    private Integer status;
    private String remark;
    private String createdAt;
    private String updatedAt;
}
