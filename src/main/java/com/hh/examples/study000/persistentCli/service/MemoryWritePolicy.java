package com.hh.examples.study000.persistentCli.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class MemoryWritePolicy {

    private static final List<String> MEMORY_HINTS = List.of(
            "记住", "请记住", "我是", "我叫", "我的名字", "我喜欢", "我偏好", "以后", "长期偏好",
            "remember", "my name", "i like", "preference");

    public boolean shouldRemember(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String normalized = userInput.toLowerCase(Locale.ROOT);
        return MEMORY_HINTS.stream().anyMatch(normalized::contains);
    }
}
