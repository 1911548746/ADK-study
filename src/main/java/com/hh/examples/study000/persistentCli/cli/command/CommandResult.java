package com.hh.examples.study000.persistentCli.cli.command;

import com.google.adk.sessions.Session;
import com.hh.examples.study000.persistentCli.entity.ConversationEntity;

/**
 * 命令执行后的 CLI 上下文。
 *
 * <p>某些命令会切换当前 Session，例如 /new、/resume、/delete 当前会话。
 * Application 只需要接收新的上下文并继续对话循环。
 */
public record CommandResult(
        Session session,
        ConversationEntity conversation,
        boolean exit) {
}
