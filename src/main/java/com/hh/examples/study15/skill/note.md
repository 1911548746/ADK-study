# study15.skill 讨论笔记

## 1. Skill 的核心定位

Skill 不是普通 Tool，也不是 SubAgent。

它更像是一份可以按需加载的操作手册：

```text
Skill = instructions + resources + metadata
```

它解决的问题是：

```text
同一个 Agent 在不同任务下，需要加载不同的工作方法、格式规范、模板、SOP 或领域规程。
```

所以 Skill 的重点不是“做事”，而是“告诉 Agent 应该怎么做”。

## 2. Skill 和 Tool 的边界

一句话区分：

```text
Tool 负责提供能力。
Skill 负责提供操作手册。
```

例如：

```text
查询天气 -> Tool
调用后端接口 -> Tool
运行脚本 -> Tool
写事故复盘的方法 -> Skill
生成发布说明的格式规范 -> Skill
代码审查检查清单 -> Skill
```

如果某个能力需要真正执行外部动作，例如读文件、查数据库、调用 HTTP、运行脚本，应该做成 Tool。

如果某个能力主要是规则、步骤、模板、示例、判断标准，适合做成 Skill。

## 3. 带脚本的 Skill 属于什么

有些 Skill 包除了 `SKILL.md`，还会带：

```text
references/
assets/
scripts/
```

例如：

```text
my-diagnostic-skill/
├── SKILL.md
├── references/
│   └── troubleshooting-guide.md
└── scripts/
    └── collect-logs.ps1
```

这种 Skill 可以理解为：

```text
带可执行资源的操作手册型 Skill
```

它仍然首先是 Skill，不自动等于 Workflow。

原因是：`SkillToolset` 的 `load_skill_resource` 只是让 Agent 加载资源内容，并不会自动执行 `scripts/` 里的脚本。

如果脚本需要真正运行，仍然要通过 Tool、业务代码或受控执行器来完成。

## 4. Skill 中设计的流程是不是 Workflow

不一定。

如果 Skill 里写了：

```text
1. 先运行脚本 A
2. 如果 A 成功，再调用工具 B
3. 最后汇总结果
```

这更像是：

```text
LLM-driven soft workflow
```

它是一种由模型根据 instructions 推进的软流程。

真正的 Workflow 通常意味着：

```text
步骤顺序、分支、重试、失败处理由代码或框架强制控制。
```

因此：

```text
Skill 适合表达“应该怎么做”。
Workflow 适合保证“必须这么做”。
```

## 5. Skill 流程失败时的风险

如果 Skill 中的某一步执行失败，例如某个脚本失败，模型可能会继续推进流程。

原因是 Skill 只是 instructions，不是强制执行语义。

即使 Skill 里写了：

```text
如果脚本失败，必须停止。
```

模型仍然可能根据上下文自行猜测、跳步或继续后续流程。

所以生产中不要只靠 Skill 约束关键流程。

更稳妥的做法是：

```text
Tool 返回结构化错误
Callback / Plugin 拦截错误
Workflow / 业务代码强制停止
```

例如 Tool 不要只返回自然语言错误：

```text
脚本执行失败：connection timeout
```

而应该返回结构化结果：

```json
{
  "status": "failed",
  "step": "run-diagnostic-script",
  "recoverable": false,
  "next_action": "stop",
  "error": "connection timeout"
}
```

然后由代码层决定是否终止流程，而不是让模型自由发挥。

## 6. Skill 和 SubAgent 的边界

一句话区分：

```text
Skill 是操作手册。
SubAgent 是执行角色。
```

也可以这样理解：

```text
Skill 不改变执行主体。
SubAgent 会切换执行主体。
```

适合使用 Skill 的场景：

```text
同一个 Agent 需要按不同任务加载不同规程
主要变化是 prompt、模板、检查清单、操作步骤
不需要独立工具权限
不需要独立模型
不需要独立 Agent 身份
```

适合使用 SubAgent 的场景：

```text
需要一个独立专家接管任务
需要不同 instruction
需要不同 tools
需要不同模型
需要独立职责边界
需要在 Event 中区分执行者
需要参与 MultiAgent / Workflow 编排
```

例如：

```text
release-agent
  -> 加载 release-note Skill

security-review-agent
  -> 加载 security-checklist Skill

incident-agent
  -> 加载 incident-summary Skill
  -> 调用 log-query Tool
  -> 调用 diagnostic Tool
```

这里 SubAgent 解决“谁来做”，Skill 解决“按什么方法做”。

## 7. Skill、Tool、SubAgent、Workflow 的组合关系

可以用这组边界记忆：

```text
Skill = 知识和规程的模块化
Tool = 外部能力的模块化
SubAgent = 执行职责的模块化
Workflow = 控制流程的模块化
```

生产中常见结构：

```text
Coordinator Agent
  -> Research Agent
       -> research-plan Skill
       -> search Tool
       -> document-reader Tool
  -> Coding Agent
       -> code-change Skill
       -> file-edit Tool
  -> Review Agent
       -> code-review Skill
       -> test Tool
```

主 Agent 负责路由。

SubAgent 负责专业职责。

Skill 负责操作规程。

Tool 负责真实动作。

Workflow 或业务代码负责强制流程。

## 8. Codex Skill 和 ADK Skill 的区别

在 Codex 中自定义的 Skill，通常是一套给 Codex 使用的本地工作规程。

它可以指导 Codex：

```text
什么时候拆任务
什么时候开子任务
子任务应该拿到什么上下文
子任务应该返回什么格式
主任务如何汇总
```

但 Codex Skill 本身不等于 ADK 的 `SkillToolset`。

Codex 中的“开一个子 Agent 去收集资料”，更像是：

```text
Skill 指导的动态任务编排
```

真正创建临时 worker、子任务或新线程的能力，来自 Codex 运行环境或工具层。

也就是说：

```text
Codex Skill 负责描述编排规则。
Codex runtime/tooling 负责创建和管理子任务。
```

这不应直接等同于 ADK 中预定义的 `subAgents(...)`。

## 9. ADK 中如何实现类似 Codex 的动态任务拆分

如果角色稳定，使用 SubAgent：

```text
coordinator-agent
  -> research-agent
  -> coding-agent
  -> review-agent
```

如果任务是动态拆出来的，例如：

```text
根据问题临时创建 3 个 research worker
每个 worker 研究不同方向
最后统一汇总
```

更适合放在应用层 orchestration：

```text
主 Agent 生成任务拆分计划
-> Java 代码根据计划创建 worker Agent / Runner 调用
-> 并行执行
-> 收集结果
-> 再交给主 Agent 汇总
```

也可以封装成一个 Tool：

```text
research_task_tool(query, scope, output_format)
```

这个 Tool 内部可以启动其他 Agent、调用搜索服务、调用 RAG 或并行执行多个 worker。

对主 Agent 来说，它只是调用一个 Tool。

## 10. LocalSkillSource 的文件规则

ADK Java 的 `LocalSkillSource` 有几个容易踩坑的规则。

### Skill 名称必须是 kebab-case

合法：

```text
incident-summary
release-note
```

不合法：

```text
incident_summary
release_note
IncidentSummary
```

### 目录名必须和 frontmatter.name 完全一致

正确：

```text
skills/
└── release-note/
    └── SKILL.md
```

```yaml
---
name: release-note
description: 将零散变更整理为发布说明。
---
```

错误：

```text
skills/
└── release_note/
    └── SKILL.md
```

```yaml
name: release-note
```

因为目录名和 `name` 不一致。

### allowedTools 要写成 allowed-tools

Java API 中是：

```java
Frontmatter.builder().allowedTools("none")
```

但本地 `SKILL.md` 中要写：

```yaml
allowed-tools: none
```

不能写：

```yaml
allowedTools: none
```

### Resource 路径有限制

`load_skill_resource` 只允许加载这些前缀下的资源：

```text
references/
assets/
scripts/
```

合法：

```text
references/release-note-example.md
assets/example.png
scripts/collect-logs.ps1
```

不合法：

```text
examples/release-note-example.md
templates/incident-template.md
```

如果 Skill instructions 中提示模型加载 resource，也应该使用合法路径。

## 11. 最终判断口诀

可以用下面这组问题判断该用什么：

```text
只是需要一份说明书？用 Skill。
需要执行外部动作？用 Tool。
需要换一个专家角色？用 SubAgent。
需要强制步骤顺序和失败处理？用 Workflow 或业务代码。
需要动态开临时 worker？用应用层 orchestration 或 Tool 封装。
```
