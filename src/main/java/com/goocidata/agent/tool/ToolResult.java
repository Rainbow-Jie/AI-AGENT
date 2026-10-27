package com.goocidata.agent.tool;

import lombok.Data;

/**
 * 工具执行结果
 *
 * @author nzj
 */
@Data
public class ToolResult {

    private boolean ok;
    private String output;
    private long elapsedMs;

    public static ToolResult ok(String output, long elapsedMs) {
        ToolResult r = new ToolResult();
        r.setOk(true);
        r.setOutput(output);
        r.setElapsedMs(elapsedMs);
        return r;
    }

    public static ToolResult fail(String message, long elapsedMs) {
        ToolResult r = new ToolResult();
        r.setOk(false);
        r.setOutput(message);
        r.setElapsedMs(elapsedMs);
        return r;
    }
}
