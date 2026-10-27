package com.goocidata.agent.tool;

import lombok.Data;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具执行上下文：一次对话内固定的权限边界
 *
 * @author nzj
 */
@Data
public class ToolContext {

    /** 沙箱根目录，所有文件操作必须落在其内 */
    private Path root;

    /** read / write / full */
    private String mode;

    /** 命令白名单 */
    private String allowCmds;

    /** 自定义命令黑名单 */
    private String denyCmds;

    /** 单次命令超时（秒） */
    private int timeoutSec = 30;

    /** 单次输出字符上限 */
    private int maxOutput = 8000;

    /**
     * 本轮提问内的调用结果指纹：key = 工具名 + 规范化参数，value = 上次输出。
     * 用于识别「模型原地打转、重复执行同一个调用」——这类情况再回灌一遍完整输出
     * 纯属浪费 token，直接告诉它结果没变即可。
     */
    private final Map<String, String> resultFingerprint = new HashMap<>();
}
