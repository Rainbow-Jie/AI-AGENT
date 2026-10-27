package com.goocidata.agent.tool;

/**
 * 工具执行过程中的可控异常：不中断对话，而是把原因回给模型让它自己调整
 *
 * @author nzj
 */
public class ToolException extends RuntimeException {

    public ToolException(String message) {
        super(message);
    }
}
