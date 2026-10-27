package com.goocidata.agent.config;

import com.goocidata.agent.common.IdGen;
import com.goocidata.agent.entity.AgentInfo;
import com.goocidata.agent.entity.LlmConfig;
import com.goocidata.agent.entity.SysUser;
import com.goocidata.agent.mapper.AgentInfoMapper;
import com.goocidata.agent.mapper.LlmConfigMapper;
import com.goocidata.agent.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 首次启动初始化：管理员账号、DeepSeek 配置占位、示例智能体
 *
 * @author nzj
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitRunner implements ApplicationRunner {

    private final SysUserMapper userMapper;
    private final LlmConfigMapper llmConfigMapper;
    private final AgentInfoMapper agentMapper;

    @Value("${agent-hub.init-admin.username:admin}")
    private String adminUsername;

    /**
     * 初始管理员密码。留空则首次启动随机生成 12 位并只在日志里打印一次——
     * 千万不要把真实密码写成默认值，这个仓库是要公开的。
     */
    @Value("${agent-hub.init-admin.password:}")
    private String adminPassword;

    @Value("${agent-hub.init-admin.nickname:系统管理员}")
    private String adminNickname;

    @Override
    public void run(ApplicationArguments args) {
        initAdmin();
        initLlm();
        initAgent();
    }

    private void initAdmin() {
        if (userMapper.count() > 0) {
            return;
        }
        // 生产环境不要把密码写在配置文件里：留空就随机生成一个，
        // 只在首次启动的日志里出现一次，登录后立刻改掉即可。
        boolean generated = adminPassword == null || adminPassword.trim().isEmpty();
        String password = generated ? randomPassword() : adminPassword.trim();

        SysUser user = new SysUser();
        user.setId(IdGen.next());
        user.setUsername(adminUsername);
        user.setPassword(new BCryptPasswordEncoder().encode(password));
        user.setNickname(adminNickname);
        user.setRole("ADMIN");
        user.setStatus(1);
        user.setCreatedAt(IdGen.now());
        user.setUpdatedAt(user.getCreatedAt());
        userMapper.insert(user);

        if (generated) {
            log.warn("未配置管理员初始密码，已随机生成 ↓↓↓ 请立刻用它登录并修改");
            log.warn("    账号：{}    密码：{}", adminUsername, password);
        } else {
            log.info("已初始化管理员账号：{}（密码取自配置，请登录后尽快修改）", adminUsername);
        }
    }

    /** 12 位易读随机密码：去掉了 0/O/1/l/I 这类容易看错的字符 */
    private String randomPassword() {
        String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private void initLlm() {
        if (llmConfigMapper.count() > 0) {
            return;
        }
        LlmConfig cfg = new LlmConfig();
        cfg.setId(IdGen.next());
        cfg.setName("DeepSeek 官方");
        cfg.setProvider("deepseek");
        cfg.setBaseUrl("https://api.deepseek.com");
        cfg.setApiKey("");
        cfg.setModel("deepseek-flash");
        cfg.setTemperature(0.7D);
        cfg.setMaxTokens(2048);
        cfg.setIsDefault(1);
        cfg.setStatus(1);
        cfg.setRemark("在管理后台填入 API Key 后即可对话。Key 申请地址：platform.deepseek.com");
        cfg.setCreatedAt(IdGen.now());
        cfg.setUpdatedAt(cfg.getCreatedAt());
        llmConfigMapper.insert(cfg);
        log.info("已创建 DeepSeek 默认配置（待填写 API Key）");
    }

    private void initAgent() {
        if (agentMapper.count() > 0) {
            return;
        }
        AgentInfo agent = new AgentInfo();
        agent.setId(IdGen.next());
        agent.setName("通用助手");
        agent.setAvatar("🤖");
        agent.setDescription("通用问答助手，未绑定知识库时只依赖模型自身知识");
        agent.setSystemPrompt("你是一个专业、严谨的智能助手，请用简洁清晰的中文回答用户问题。"
                + "遇到不确定的信息要明确说明不确定，不要编造。");
        agent.setWelcomeMsg("你好，我是通用助手。有什么可以帮你的？");
        agent.setKbIds("");
        agent.setTopK(4);
        agent.setStatus(1);
        agent.setCreatedAt(IdGen.now());
        agent.setUpdatedAt(agent.getCreatedAt());
        agentMapper.insert(agent);
        log.info("已创建示例智能体：通用助手");
    }
}
