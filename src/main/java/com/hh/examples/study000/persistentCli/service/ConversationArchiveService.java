package com.hh.examples.study000.persistentCli.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hh.examples.study000.persistentCli.entity.ConversationEntity;
import com.hh.examples.study000.persistentCli.entity.MessageEntity;
import com.hh.examples.study000.persistentCli.mapper.ConversationMapper;
import com.hh.examples.study000.persistentCli.mapper.MessageMapper;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ConversationArchiveService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ConversationArchiveService(ConversationMapper conversationMapper, MessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    public ConversationEntity findOrCreateConversation(String appName, String userId, String sessionId) {
        ConversationEntity existing = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getAppName, appName)
                .eq(ConversationEntity::getUserId, userId)
                .eq(ConversationEntity::getSessionId, sessionId));
        if (existing != null) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        ConversationEntity conversation = ConversationEntity.builder()
                .appName(appName)
                .userId(userId)
                .sessionId(sessionId)
                .title("CLI conversation " + sessionId)
                .createdAt(now)
                .updatedAt(now)
                .build();
        conversationMapper.insert(conversation);
        return conversation;
    }

    public void saveUserMessage(Long conversationId, String appName, String userId, String sessionId, String text) {
        saveMessage(conversationId, appName, userId, sessionId, "user", text);
    }

    public void saveAssistantMessage(Long conversationId, String appName, String userId, String sessionId, String text) {
        saveMessage(conversationId, appName, userId, sessionId, "assistant", text);
    }

    public void deleteConversation(String appName, String userId, String sessionId) {
        // 删除业务侧完整聊天记录；长期 Memory 是跨 Session 资产，不在这里级联删除。
        messageMapper.delete(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getAppName, appName)
                .eq(MessageEntity::getUserId, userId)
                .eq(MessageEntity::getSessionId, sessionId));
        conversationMapper.delete(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getAppName, appName)
                .eq(ConversationEntity::getUserId, userId)
                .eq(ConversationEntity::getSessionId, sessionId));
    }

    private void saveMessage(
            Long conversationId,
            String appName,
            String userId,
            String sessionId,
            String role,
            String text) {
        // messages 是业务留档，永远保存完整文本，不参与 ADK event compaction。
        MessageEntity message = MessageEntity.builder()
                .conversationId(conversationId)
                .appName(appName)
                .userId(userId)
                .sessionId(sessionId)
                .role(role)
                .contentText(text)
                .contentJson(Content.fromParts(Part.fromText(text)).toJson())
                .createdAt(LocalDateTime.now())
                .build();
        messageMapper.insert(message);

        ConversationEntity update = new ConversationEntity();
        update.setUpdatedAt(LocalDateTime.now());
        conversationMapper.update(update, new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getId, conversationId));
    }
}
