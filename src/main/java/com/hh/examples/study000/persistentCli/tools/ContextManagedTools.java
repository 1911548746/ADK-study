package com.hh.examples.study000.persistentCli.tools;

import com.google.adk.tools.Annotations.Schema;
import com.hh.examples.study000.persistentCli.service.ToolResultExternalizationService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ContextManagedTools {

    private static ToolResultExternalizationService externalizationService;

    private ContextManagedTools() {
    }

    public static void configure(ToolResultExternalizationService service) {
        externalizationService = service;
    }

    @Schema(
            name = "read_text_file",
            description = """
                    读取本地文本文件。若文件内容超过 50KB，工具不会把完整内容直接放入上下文，
                    而是保存到磁盘并只返回 2KB 预览、resultId 和后续读取建议。
                    """)
    public static Map<String, Object> readTextFile(
            @Schema(name = "path", description = "需要读取的本地文本文件路径") String path) {
        ensureConfigured();
        Path file = Path.of(path).toAbsolutePath().normalize();
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("文件不存在: " + file);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("不是普通文件: " + file);
        }

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return externalizationService.externalizeIfNeeded(
                    "read_text_file",
                    file.toString(),
                    content);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + file, e);
        }
    }

    @Schema(
            name = "read_externalized_result",
            description = """
                    按窗口读取已经外部化保存的工具结果。用于继续查看 read_text_file 或未来其他工具
                    返回的大结果。该工具最多返回 16KB 内容，不会再次嵌套外部化。
                    """)
    public static Map<String, Object> readExternalizedResult(
            @Schema(name = "resultId", description = "外部化工具结果的 resultId") String resultId,
            @Schema(name = "offset", description = "从第几个字符开始读取，首次通常为 0") int offset,
            @Schema(name = "limit", description = "本次最多读取多少字符，最大会被限制为 16384") int limit) {
        ensureConfigured();
        return externalizationService.readWindow(resultId, offset, limit);
    }

    private static void ensureConfigured() {
        if (externalizationService == null) {
            throw new IllegalStateException("ContextManagedTools 尚未完成 Spring Bean 配置");
        }
    }
}
