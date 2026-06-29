package com.hh.examples.study000.persistentCli.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.MemoryEntry;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import com.hh.examples.study000.persistentCli.entity.MemoryEntity;
import com.hh.examples.study000.persistentCli.mapper.MemoryMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class MySqlMemoryService implements BaseMemoryService {

    private final MemoryMapper memoryMapper;
    private final MemoryWritePolicy memoryWritePolicy;

    public MySqlMemoryService(MemoryMapper memoryMapper, MemoryWritePolicy memoryWritePolicy) {
        this.memoryMapper = memoryMapper;
        this.memoryWritePolicy = memoryWritePolicy;
    }

    @Override
    public Completable addSessionToMemory(Session session) {
        return Completable.fromAction(() -> {
            for (Event event : session.events()) {
                if (!isUserEvent(event) || event.content().isEmpty()) {
                    continue;
                }

                Content content = event.content().get();
                String text = content.text();
                if (text == null || text.isBlank() || !memoryWritePolicy.shouldRemember(text)) {
                    continue;
                }

                String hash = sha256(session.appName() + "|" + session.userId() + "|" + text.trim());
                boolean exists = memoryMapper.selectCount(new LambdaQueryWrapper<MemoryEntity>()
                        .eq(MemoryEntity::getAppName, session.appName())
                        .eq(MemoryEntity::getUserId, session.userId())
                        .eq(MemoryEntity::getContentHash, hash)) > 0;
                if (exists) {
                    continue;
                }

                // Memory 是给 Agent 检索的长期事实，不等同于完整聊天记录。
                MemoryEntity memory = MemoryEntity.builder()
                        .appName(session.appName())
                        .userId(session.userId())
                        .sourceSessionId(session.id())
                        .sourceEventId(event.id())
                        .author(event.author())
                        .contentText(text)
                        .contentJson(content.toJson())
                        .contentHash(hash)
                        .createdAt(LocalDateTime.now())
                        .build();
                memoryMapper.insert(memory);
            }
        });
    }

    @Override
    public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
        return Single.fromCallable(() -> {
            LambdaQueryWrapper<MemoryEntity> wrapper = new LambdaQueryWrapper<MemoryEntity>()
                    .eq(MemoryEntity::getAppName, appName)
                    .eq(MemoryEntity::getUserId, userId)
                    .orderByDesc(MemoryEntity::getId)
                    .last("LIMIT 5");

            if (query != null && !query.isBlank()) {
                wrapper.like(MemoryEntity::getContentText, query.trim());
            }

            List<MemoryEntry> entries = memoryMapper.selectList(wrapper)
                    .stream()
                    .map(this::toMemoryEntry)
                    .toList();

            return SearchMemoryResponse.builder()
                    .memories(entries)
                    .build();
        });
    }

    private MemoryEntry toMemoryEntry(MemoryEntity entity) {
        return MemoryEntry.builder()
                .author(entity.getAuthor() == null ? "user" : entity.getAuthor())
                .content(Content.fromJson(entity.getContentJson()))
                .timestamp(entity.getCreatedAt()
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toString())
                .build();
    }

    private boolean isUserEvent(Event event) {
        String author = event.author();
        return author != null && author.toLowerCase(Locale.ROOT).contains("user");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("计算 memory hash 失败", e);
        }
    }
}
