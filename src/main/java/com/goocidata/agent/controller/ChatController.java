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

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * 可用智能体列表（普通用户可见）
     * <p>
     * 只返回展示需要的字段。直接回 {@code AgentInfo} 实体是不行的：里面带着
     * 系统提示词、沙箱绝对路径、命令白名单——任何登录用户 curl 一下就能拿到全文，
     * 比在对话里套话容易得多，而且提示词泄露后所有防护规则都形同虚设。
     */
    @GetMapping("/agents")
    public R<List<Map<String, Object>>> agents(@RequestAttribute(AuthInterceptor.ATTR_USER) SysUser user) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AgentInfo a : agentService.list(null, true)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("name", a.getName());
            item.put("avatar", a.getAvatar());
            item.put("description", a.getDescription());
            item.put("kbNames", a.getKbNames());
            list.add(item);
        }
        return R.ok(list);
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
