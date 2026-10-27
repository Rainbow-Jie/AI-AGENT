package com.goocidata.agent.controller;

import com.goocidata.agent.common.R;
import com.goocidata.agent.entity.AgentInfo;
import com.goocidata.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能体接口（管理端）
 *
 * @author nzj
 */
@RestController
@RequestMapping("/api/admin/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @GetMapping
    public R<List<AgentInfo>> list(@RequestParam(required = false) String kw) {
        return R.ok(agentService.list(kw, false));
    }

    @GetMapping("/{id}")
    public R<AgentInfo> detail(@PathVariable String id) {
        return R.ok(agentService.detail(id));
    }

    @PostMapping("/save")
    public R<AgentInfo> save(@RequestBody AgentInfo input) {
        return R.ok(agentService.save(input));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        agentService.delete(id);
        return R.ok();
    }
}
