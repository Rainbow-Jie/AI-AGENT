package com.goocidata.agent.service;

import com.goocidata.agent.common.BizException;
import com.goocidata.agent.common.IdGen;
import com.goocidata.agent.entity.AgentInfo;
import com.goocidata.agent.entity.KnowledgeBase;
import com.goocidata.agent.entity.LlmConfig;
import com.goocidata.agent.mapper.AgentInfoMapper;
import com.goocidata.agent.mapper.KnowledgeBaseMapper;
import com.goocidata.agent.mapper.LlmConfigMapper;
import com.goocidata.agent.tool.ToolBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 智能体服务
 *
 * @author nzj
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentInfoMapper agentMapper;
    private final LlmConfigMapper llmConfigMapper;
    private final KnowledgeBaseMapper kbMapper;

    @Value("${agent-hub.tools.default-work-dir:./workspace}")
    private String defaultWorkDir;

    @Value("${agent-hub.tools.timeout-seconds:30}")
    private int defaultTimeout;

    @Value("${agent-hub.tools.max-steps:6}")
    private int defaultMaxSteps;

    public List<AgentInfo> list(String kw, boolean onlyEnabled) {
        List<AgentInfo> list = agentMapper.list(kw, onlyEnabled);
        fillExtra(list);
        return list;
    }

    public AgentInfo detail(String id) {
        AgentInfo agent = agentMapper.findById(id);
        if (agent == null) {
            throw new BizException("智能体不存在");
        }
        fillExtra(List.of(agent));
        return agent;
    }

    /**
     * 供对话链路使用，要求启用状态
     */
    public AgentInfo mustGetEnabled(String id) {
        AgentInfo agent = agentMapper.findById(id);
        if (agent == null) {
            throw new BizException("智能体不存在");
        }
        if (agent.getStatus() != null && agent.getStatus() == 0) {
            throw new BizException("该智能体已停用");
        }
        return agent;
    }

    public AgentInfo save(AgentInfo input) {
        if (input.getName() == null || input.getName().trim().isEmpty()) {
            throw new BizException(400, "请填写智能体名称");
        }
        input.setName(input.getName().trim());
        if (input.getKbIds() != null && !input.getKbIds().trim().isEmpty()) {
            List<String> ids = splitIds(input.getKbIds());
            for (String kbId : ids) {
                if (kbMapper.findById(kbId) == null) {
                    throw new BizException("知识库不存在：" + kbId);
                }
            }
            input.setKbIds(String.join(",", ids));
        } else {
            input.setKbIds("");
        }
        if (input.getStatus() == null) {
            input.setStatus(1);
        }
        if (input.getTopK() == null || input.getTopK() <= 0) {
            input.setTopK(4);
        }
        if (input.getAvatar() == null || input.getAvatar().trim().isEmpty()) {
            input.setAvatar("🤖");
        }
        normalizeTools(input);

        if (input.getId() == null || input.getId().isEmpty()) {
            input.setId(IdGen.next());
            input.setCreatedAt(IdGen.now());
            input.setUpdatedAt(input.getCreatedAt());
            agentMapper.insert(input);
        } else {
            AgentInfo db = agentMapper.findById(input.getId());
            if (db == null) {
                throw new BizException("智能体不存在");
            }
            input.setUpdatedAt(IdGen.now());
            agentMapper.update(input);
        }
        fillExtra(List.of(input));
        return input;
    }

    public void delete(String id) {
        agentMapper.delete(id);
    }

    /**
     * 规范化工具权限配置：档位取值、工作目录落成绝对真实路径、数值范围。
     * 工具关闭时不校验目录，避免用户填了无效路径就存不进去。
     */
    private void normalizeTools(AgentInfo input) {
        String mode = input.getToolMode() == null
                ? "off" : input.getToolMode().trim().toLowerCase(Locale.ROOT);
        if (!List.of("off", "read", "write", "full").contains(mode)) {
            mode = "off";
        }
        input.setToolMode(mode);

        String dir = input.getWorkDir() == null ? "" : input.getWorkDir().trim();
        if (dir.isEmpty()) {
            dir = defaultWorkDir;
        }
        if ("off".equals(mode)) {
            input.setWorkDir(dir);
        } else {
            try {
                Path p = Paths.get(dir);
                if (!p.isAbsolute()) {
                    p = Paths.get("").toAbsolutePath().resolve(p);
                }
                p = p.normalize();
                Files.createDirectories(p);
                p = p.toRealPath();
                if (!Files.isDirectory(p)) {
                    throw new BizException(400, "工作目录不是有效的文件夹：" + p);
                }
                input.setWorkDir(p.toString());
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException(400, "工作目录无法创建或不可访问：" + dir
                        + "（" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "）");
            }
        }

        int timeout = input.getToolTimeout() == null ? defaultTimeout : input.getToolTimeout();
        input.setToolTimeout(Math.min(300, Math.max(1, timeout)));

        int steps = input.getMaxSteps() == null ? defaultMaxSteps : input.getMaxSteps();
        input.setMaxSteps(Math.min(20, Math.max(1, steps)));

        input.setAllowCmds(input.getAllowCmds() == null ? "" : input.getAllowCmds().trim());
        input.setDenyCmds(input.getDenyCmds() == null ? "" : input.getDenyCmds().trim());
    }

    public void fillExtra(List<AgentInfo> list) {
        if (list.isEmpty()) {
            return;
        }
        Map<String, String> kbNameMap = kbMapper.listAll().stream()
                .collect(Collectors.toMap(KnowledgeBase::getId, KnowledgeBase::getName, (a, b) -> a));
        Map<String, String> modelMap = llmConfigMapper.listAll().stream()
                .collect(Collectors.toMap(LlmConfig::getId, c -> c.getModel(), (a, b) -> a));

        for (AgentInfo a : list) {
            // 空字符串表示跟随默认模型，不能当成"引用了已删除的配置"
            if (a.getLlmConfigId() != null && !a.getLlmConfigId().trim().isEmpty()) {
                a.setModelName(modelMap.getOrDefault(a.getLlmConfigId(), "已删除的配置"));
            } else {
                a.setModelName("默认模型");
            }
            List<String> ids = splitIds(a.getKbIds());
            if (ids.isEmpty()) {
                a.setKbNames("");
            } else {
                a.setKbNames(ids.stream()
                        .map(id -> kbNameMap.getOrDefault(id, "已删除"))
                        .collect(Collectors.joining("、")));
            }

            String mode = a.getToolMode() == null ? "off" : a.getToolMode();
            if ("off".equals(mode)) {
                a.setToolScopeDesc("仅对话（未开启工具）");
            } else {
                a.setToolScopeDesc(ToolBox.modeDesc(mode) + "｜目录 " + a.getWorkDir());
            }
        }
    }

    public static List<String> splitIds(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return result;
        }
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(result::add);
        return result;
    }
}
