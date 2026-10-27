package com.goocidata.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;

/**
 * Agent Hub 启动类
 *
 * @author nzj
 */
@SpringBootApplication
@EnableScheduling
public class AgentHubApplication {

    public static void main(String[] args) {
        // SQLite 数据文件所在目录必须先存在，否则数据源初始化会失败
        File dataDir = new File("./data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            System.err.println("无法创建数据目录：" + dataDir.getAbsolutePath());
        }
        SpringApplication.run(AgentHubApplication.class, args);
    }
}
