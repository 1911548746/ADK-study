package com.hh.examples.study000.persistentCli.cli;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PersistentCliAgentTest {

    @Autowired
    private PersistentCliApplication persistentCliApplication;

    @Test
    void runCli() {
        // 这是学习示例的交互式入口：直接在 IDE 中运行该测试方法即可进入命令行。
        persistentCliApplication.run();
    }
}
