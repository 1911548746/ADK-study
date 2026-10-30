# study18.hitl.fullVersion

## 概念

这个包将第 18 章的命令行退款确认示例接入 Spring Boot。重点不是让 HTTP 请求一直挂起等待人工，而是把 HITL 拆成两个独立请求：

```text
POST /chat
  -> Agent 调用 request_refund
  -> ToolContext.requestConfirmation(...)
  -> 202 PENDING_CONFIRMATION

人工在页面审批
  -> POST /chat/confirm
  -> 带 adk_request_confirmation 的 FunctionResponse 恢复原 Session
  -> Tool 根据 toolConfirmation() 执行或拒绝退款
  -> 200 COMPLETED
```

`PendingConfirmation.confirmationCallId` 是协议关键。确认接口必须回传这个内部确认调用的 ID，不能使用原始的 `request_refund` Tool Call ID。

## ADK 实现

- `RefundTools.requestRefund(...)` 在第一次执行时调用 `ToolContext.requestConfirmation(...)`，没有实际退款副作用。
- ADK 产生名为 `adk_request_confirmation` 的 FunctionCall。
- `SpringBootAgentService` 从 Event 取出其 ID，返回给 HTTP 调用方。
- `/chat/confirm` 将 `{ "confirmed": true | false }` 封装为同名 `FunctionResponse`，使用相同的 `userId` 和 `sessionId` 再次调用 `Runner.runAsync(...)`。
- ADK 恢复原始工具调用，并把人工决定注入 `ToolContext.toolConfirmation()`。

这与 LangGraph 的 `interrupt()` 后以相同 `thread_id` 调用 `Command(resume=...)` 对应；ADK 的恢复载体是 Session Event 和确认 FunctionResponse。

## 接口

所有端点以 `/api/study18` 为前缀。

### 发起退款对话

```powershell
$first = Invoke-WebRequest `
  -Method Post `
  -Uri http://localhost:8080/api/study18/chat `
  -ContentType 'application/json' `
  -Body '{"userId":"u1","sessionId":"refund-001","message":"请为订单 ORD-20260731-001 退款 12800 分，原因是重复扣款。"}'

$first.StatusCode
$pending = $first.Content | ConvertFrom-Json
$pending
```

预期 HTTP 状态为 `202`，响应 `status` 为 `PENDING_CONFIRMATION`。审批页面应基于 `confirmation.arguments` 显示订单、金额等信息，并保存：

```json
{
  "confirmationCallId": "...",
  "userId": "u1",
  "sessionId": "refund-001"
}
```

### 通过审批并恢复

```powershell
$body = @{
  userId = $pending.userId
  sessionId = $pending.sessionId
  confirmationCallId = $pending.confirmation.confirmationCallId
  approved = $true
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/study18/chat/confirm `
  -ContentType 'application/json' `
  -Body $body
```

将 `approved` 改为 `false` 可观察 `rejected_by_human`，工具不会执行退款。通过时则会产生模拟的 `refund_executed` 结果。

### SSE

```powershell
curl.exe -N -X POST http://localhost:8080/api/study18/chat/stream `
  -H "Content-Type: application/json" `
  -d "{\"userId\":\"u1\",\"sessionId\":\"refund-stream-001\",\"message\":\"请为订单 ORD-20260731-002 退款 5000 分，原因是重复扣款。\"}"
```

初次流中会依次得到 `meta`、`trace`、`confirmation_required`、`done`。不要保持这条连接等待审批；收到 `confirmation_required` 后关闭已完成的流，并让前端展示审批 UI。

审批后调用 `POST /chat/confirm/stream`，请求体与 `/chat/confirm` 相同。该流将输出工具最终结果和 `done`。

## 生产注意事项

- `InMemorySessionService` 仅用于本地实验。跨重启、跨实例、长审批必须替换为持久化 `SessionService`。
- 审批记录应单独持久化，维护 `PENDING/APPROVED/REJECTED/COMPLETED` 状态和审批人、审批时间、过期时间。
- 服务端应从认证上下文取得 `userId`，而不是信任请求体。
- 退款等外部副作用必须以原始 Tool Call ID 或业务幂等键保证幂等，并在审批恢复时重新校验订单状态与金额。
- 本示例为学习而允许客户端提交 `confirmationCallId`。生产系统还应校验它属于当前用户、当前 Session 的待审批记录，且只能确认一次。
