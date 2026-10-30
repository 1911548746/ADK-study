# study18.hitl

## 概念

HITL（Human in the Loop）是在 Agent 即将执行高风险操作时，将最终决定权交给人。它不是让模型在文本里问一句“是否确认”，而是暂停实际 Tool 执行，等待一个可关联、可审计、可恢复的确认结果。

```text
模型选择高风险 Tool
  -> Tool 请求确认
  -> ADK 发出 Pending Confirmation Event
  -> 人工批准 / 拒绝
  -> 确认结果写入同一 Session
  -> Runner 恢复原始 Tool Call
  -> Tool 根据 confirmed 执行或拒绝操作
```

本章以“退款”为例。查询订单、计算金额可以自动化；真正退款会影响资金，必须经过人工批准。

## LangGraph 对照

LangGraph 常见做法是在危险节点之前使用 `interrupt()`，通过 checkpointer 保存图状态，人工用 `Command(resume=...)` 提交决定后继续运行。

```text
LangGraph interrupt + Command(resume)
    ≈ ADK ToolContext.requestConfirmation + 用户 FunctionResponse

LangGraph checkpoint
    ≈ ADK Session events

LangGraph interrupt payload
    ≈ ToolConfirmation.hint + payload
```

二者共同的设计要点是：暂停点必须位于真实副作用之前，恢复时必须关联到原始请求，且批准与拒绝都必须进入审计历史。

## ADK 实现

ADK Java 的 Tool 可以声明一个 `ToolContext` 参数。第一次执行时，调用：

```java
toolContext.requestConfirmation(
        "即将为订单 ORD-20260731-001 退款 12800 分，是否批准？",
        Map.of("orderId", orderId, "amountCents", amountCents));
```

ADK 随后生成一个内部工具调用，名称为 `adk_request_confirmation`。其中包含：

- 原始 `request_refund` FunctionCall。
- `ToolConfirmation` 的提示语和 payload。
- 一个新的 confirmation FunctionCall ID。

应用层应将这些内容展示给审批人。批准或拒绝后，应用要将同一个 confirmation FunctionCall ID 写回：

```java
FunctionResponse.builder()
        .id(confirmationCallId)
        .name("adk_request_confirmation")
        .response(Map.of("confirmed", true))
        .build();
```

这条 FunctionResponse 作为用户内容，通过同一个 `userId`、`sessionId` 再次传入 `runner.runAsync(...)`。ADK 会恢复原始 Tool Call，并在重新执行 Tool 时通过 `toolContext.toolConfirmation()` 注入人工决定。

Tool 必须自行检查拒绝结果，绝不能把“请求过确认”误当作“已经批准”：

```java
ToolConfirmation confirmation = toolContext.toolConfirmation().orElse(null);
if (confirmation == null) {
    toolContext.requestConfirmation("...", payload);
    return Map.of("status", "pending_human_confirmation");
}

if (!confirmation.confirmed()) {
    return Map.of("status", "rejected_by_human");
}

return executeRealRefund();
```

## 完整代码

```text
src/main/java/com/hh/examples/study18/hitl/HitlExample.java
src/main/java/com/hh/examples/study18/hitl/RefundTools.java
```

`HitlExample` 会依次运行两个独立 Session：

- 批准退款：Tool 返回 `refund_executed`。
- 拒绝退款：Tool 返回 `rejected_by_human`，不会执行退款。

为了使示例可重复运行，人工决定由 `runScenario(true)` 和 `runScenario(false)` 模拟。真实系统中，这两个决定应来自审批页面、客服后台、CLI 或 WebSocket/SSE 客户端。

## 运行方式

确认公共模型配置支持 Tool Calling 后，在项目根目录执行：

```powershell
$env:JAVA_HOME='D:\coding\Environment\Java\jdk17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn compile
```

然后在 IDE 中运行：

```text
com.hh.examples.study18.hitl.HitlExample
```

## 运行流程

```text
用户请求退款
  -> LlmAgent 调用 request_refund
  -> RefundTools 发现尚无 ToolConfirmation
  -> requestConfirmation(...)
  -> Event: adk_request_confirmation
  -> 程序取出 confirmationCallId
  -> 模拟人工提交 confirmed=true / false
  -> 同一 Session 上再次 runner.runAsync(...)
  -> ADK 恢复原 request_refund
  -> Tool 根据 toolConfirmation.confirmed() 分支
  -> Agent 输出最终结果
```

## 关键源码分析

### 暂停点在 Tool 内，而不在 prompt 内

`request_refund` 的危险副作用位于确认判断之后。即使模型错误地声称“已经退款”，实际退款也无法发生，因为 Tool 尚未走到执行分支。

```java
if (confirmation == null) {
    toolContext.requestConfirmation(...);
    return Map.of("status", "pending_human_confirmation");
}
```

这就是 HITL 的治理边界：模型负责提出 Action，人负责授权，Tool 负责强制执行策略。

### 关联 ID 不能丢失

确认结果必须使用 `adk_request_confirmation` 的 FunctionCall ID：

```java
FunctionCall confirmationCall = findConfirmationCall(pendingEvents);
Content humanDecision = createHumanDecision(confirmationCall, approved);
```

不要用原始 `request_refund` 的 ID，也不要自行生成随机 ID。ADK 依靠 confirmation call ID 找回原始 FunctionCall 和待确认参数。

### Session 是恢复载体

初次请求和人工确认必须使用相同的：

```text
appName + userId + sessionId
```

`InMemoryRunner` 仅用于本地学习。生产系统的审批可能跨进程、跨机器、跨数小时，因此应使用持久化 Session Service，并额外保存审批记录。

## 可观察结果

批准路径的关键事件：

```text
[Tool Call]
request_refund(...)

[Pending Confirmation]
adk_request_confirmation(...)

confirmed = true

[Tool Response]
status=refund_executed
```

拒绝路径则应看到：

```text
confirmed = false

[Tool Response]
status=rejected_by_human
message=人工审批拒绝，未执行退款。
```

两条路径都应产生最终回答，但只有批准路径能出现 `refund_executed`。

## 生产实践

- 审批页只展示脱敏后的订单、金额、影响范围和风险说明，不能信任模型生成的参数摘要。
- 服务端应基于原始 Tool 参数、用户权限、金额阈值和业务规则再次校验审批请求。
- 审批结果应记录审批人、时间、理由、原始 FunctionCall ID、最终执行结果和幂等键。
- Tool 的实际副作用必须具有幂等性，避免网络重试或重复恢复造成二次退款。
- 对低风险查询工具不要滥用 HITL；它会增加延迟和人工负担。
- 对多步骤 Workflow，确认恢复后应验证后续 Agent 是否按预期继续执行。当前项目已升级到 ADK 1.7.0，适合进一步实验可恢复的 SequentialAgent。

## 思考题

1. 为什么“模型先回复用户说正在等待确认”不能替代 `requestConfirmation(...)`？
2. 对退款、发邮件、删除数据和生产发布，审批 payload 应分别包含哪些风险信息？
3. 如果审批人在 Agent 等待期间修改了订单金额，恢复原始 Tool Call 前应该怎样处理？
