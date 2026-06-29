package com.hh.examples.study000.persistentCli.service;

import com.hh.examples.study000.persistentCli.support.PersistentCliJson;
import com.hh.examples.study000.persistentCli.support.PersistentCliProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ToolResultExternalizationService {

    private static final int EXTERNALIZE_THRESHOLD_BYTES = 50 * 1024;
    private static final int PREVIEW_BYTES = 2 * 1024;
    private static final int MAX_WINDOW_CHARS = 16 * 1024;
    private static final DateTimeFormatter DIRECTORY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Path storageDir;

    public ToolResultExternalizationService() {
        this.storageDir = Path.of(PersistentCliProperties.TOOL_RESULT_DIR).toAbsolutePath().normalize();
    }

    public Map<String, Object> externalizeIfNeeded(String toolName, String source, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= EXTERNALIZE_THRESHOLD_BYTES) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("externalized", false);
            result.put("toolName", toolName);
            result.put("source", source);
            result.put("content", content);
            result.put("contentBytes", bytes.length);
            return result;
        }

        try {
            Files.createDirectories(storageDir);
            String resultId = newResultId(toolName);
            Path contentPath = storageDir.resolve(resultId + ".txt");
            Path metadataPath = storageDir.resolve(resultId + ".json");
            Files.writeString(contentPath, content, StandardCharsets.UTF_8);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("resultId", resultId);
            metadata.put("toolName", toolName);
            metadata.put("source", source);
            metadata.put("contentPath", contentPath.toString());
            metadata.put("originalBytes", bytes.length);
            metadata.put("contentHash", sha256(bytes));
            metadata.put("createdAt", LocalDateTime.now().toString());
            Files.writeString(metadataPath, PersistentCliJson.toJson(metadata), StandardCharsets.UTF_8);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("externalized", true);
            result.put("toolName", toolName);
            result.put("resultId", resultId);
            result.put("source", source);
            result.put("originalBytes", bytes.length);
            result.put("previewBytes", PREVIEW_BYTES);
            result.put("preview", preview(content, PREVIEW_BYTES));
            result.put("coverage", "preview_only");
            result.put("hasMore", true);
            result.put("fullContentPath", contentPath.toString());
            result.put("recommendedNextTool", "read_externalized_result");
            result.put("recommendedNextArgs", Map.of(
                    "resultId", resultId,
                    "offset", 0,
                    "limit", 8192));
            result.put("incompleteWarning", "当前工具结果只是预览，不代表完整内容。若回答依赖完整证据，请继续调用 read_externalized_result 分片读取。");
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("外部化工具结果失败: " + source, e);
        }
    }

    public Map<String, Object> readWindow(String resultId, int offset, int limit) {
        int safeOffset = Math.max(offset, 0);
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 8192 : limit, MAX_WINDOW_CHARS));
        Path contentPath = resolveContentPath(resultId);

        try {
            String content = Files.readString(contentPath, StandardCharsets.UTF_8);
            int start = Math.min(safeOffset, content.length());
            int end = Math.min(start + safeLimit, content.length());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("externalized", false);
            result.put("windowRead", true);
            result.put("resultId", resultId);
            result.put("offset", start);
            result.put("limit", safeLimit);
            result.put("nextOffset", end);
            result.put("totalChars", content.length());
            result.put("hasMore", end < content.length());
            result.put("content", content.substring(start, end));
            return result;
        } catch (IOException e) {
            throw new IllegalArgumentException("读取外部化工具结果失败: " + resultId, e);
        }
    }

    private Path resolveContentPath(String resultId) {
        Path path = storageDir.resolve(resultId + ".txt").normalize();
        if (!path.startsWith(storageDir)) {
            throw new IllegalArgumentException("非法 resultId: " + resultId);
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("未找到外部化工具结果: " + resultId);
        }
        return path;
    }

    private String newResultId(String toolName) {
        String date = LocalDateTime.now().format(DIRECTORY_FORMATTER);
        return toolName + "-" + date + "-" + UUID.randomUUID();
    }

    private String preview(String content, int maxBytes) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return content;
        }

        int end = Math.min(content.length(), maxBytes);
        while (end > 0 && content.substring(0, end).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            end--;
        }
        return content.substring(0, end);
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
