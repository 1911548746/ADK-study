package com.hh.examples.study000.persistentCli.cli;

import com.hh.AdkStudyApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class PersistentCliMain {

    private PersistentCliMain() {
    }

    public static void main(String[] args) {
        // 普通 main 启动同样可以享受 Spring 管理的 MyBatis-Plus、Mapper 和 Service，
        // 但不会经过 JUnit 测试运行器，因此更适合需要 System.in 的交互式命令行。
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AdkStudyApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            context.getBean(PersistentCliApplication.class).run();
        }
    }
}
