package com.goocidata.agent.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 系统用户
 *
 * @author nzj
 */
@Data
public class SysUser {

    private String id;
    private String username;

    /** 只接收不返回：防止密码泄漏到接口响应 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private String nickname;

    /**
     * 头像。存 data URL（前端上传后压到 200×200 JPEG），
     * 为空表示没设过，前端回落到昵称首字。
     */
    private String avatar;

    /** ADMIN / USER */
    private String role;

    /** 1 启用 0 停用 */
    private Integer status;

    private String lastLoginAt;
    private String createdAt;
    private String updatedAt;
}
