package com.goocidata.agent.controller;

import com.goocidata.agent.common.R;
import com.goocidata.agent.config.AuthInterceptor;
import com.goocidata.agent.entity.AgentInfo;
import com.goocidata.agent.entity.ChatSession;
import com.goocidata.agent.entity.SysUser;
import com.goocidata.agent.service.AgentService;
import com.goocidata.agent.service.ChatService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 对话接口（登录用户可用）
 *
 * @author nzj
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final AgentService agentService;
    private final ChatService chatService;

    /**
     * 可用智能体列表（只返回启用的，普通用户可见）
     */
    @GetMapping("/agents")
    public R<List<AgentInfo>> agents(@RequestAttribute(AuthInterceptor.ATTR_USER) SysUser user) {
        return R.ok(agentService.list(null, true));
    }

    /**
     * 流式对话，返回 SSE
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestAttribute(AuthInterceptor.ATTR_USER) SysUser user,
                             @RequestBody ChatReq req) {
        return chatService.stream(user, req.getAgentId(), req.getSessionId(), req.getQuestion());
    }

    @GetMapping("/sessions")
    public R<List<ChatSession>> sessions(@RequestAttribute(AuthInterceptor.ATTR_USER) SysUser user) {
        return R.ok(chatService.listSessions(user.getId()));
    }

    @GetMapping("/sessions/{id}/messages")
    public R<List<Map<String, Object>>> messages(@PathVariable String id,
                                                 @RequestAttribute(AuthInterceptor.ATTR_USER) SysUser user) {
        return R.ok(chatService.listMessages(id, user));
    }

    @DeleteMapping("/sessions/{id}")
    public R<Void> deleteSession(@PathVariable String id,
                                 @RequestAttribute(AuthInterceptor.ATTR_USER) SysUser user) {
        chatService.deleteSession(id, user);
        return R.ok();
    }

    @PostMapping("/sessions/{id}/rename")
    public R<ChatSession> rename(@PathVariable String id, @RequestBody RenameReq req,
                                 @RequestAttribute(AuthInterceptor.ATTR_USER) SysUser user) {
        return R.ok(chatService.renameSession(id, req.getTitle(), user));
    }

    @Data
    public static class ChatReq {
        private String agentId;
        private String sessionId;
        private String question;
    }

    @Data
    public static class RenameReq {
        private String title;
    }
}
