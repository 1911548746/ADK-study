# study15.skill

## 概念

Skill 是一种可按需加载的能力说明。

它不是普通 Java 方法，也不是直接执行业务逻辑的 Tool。Skill 更像是一份带元数据的操作手册：

```text
Frontmatter：这个 Skill 是什么、适合什么场景
Instructions：使用这个 Skill 时应该遵循的详细规则
Resources：Skill 需要的模板、示例或辅助材料
```

Agent 接入 Skill 后，不需要把所有技能说明一次性塞进 instruction，而是可以先列出可用 Skill，再按任务需要加载某个 Skill。

这解决的是：

```text
系统提示词过长
不同任务需要不同操作规程
技能说明需要独立维护
Agent 需要按需获取领域知识或工作方法
```

## LangGraph 对照

在 LangGraph 中，类似能力通常会用以下方式实现：

```text
节点根据任务类型路由
-> 从配置、文件或向量库加载某套 prompt / policy
-> 将加载结果拼接到下一个 LLM 节点的 system prompt
```

也就是说，LangGraph 里 Skill 更像是“动态 prompt/resource 加载节点”。

ADK 的设计不同：它把 Skill 暴露成 Toolset。

```text
Agent
-> SkillToolset
   -> list_skills
   -> load_skill
   -> load_skill_resource
```

模型可以通过工具自己决定何时查看 Skill 列表、何时加载某个 Skill。

## ADK 实现

本章示例使用：

```java
InMemorySkillSource
SkillToolset
LlmAgent.tools(...)
```

核心结构：

```java
SkillSource skillSource = InMemorySkillSource.builder()
        .skill("incident-summary")
        .frontmatter(...)
        .instructions(...)
        .addResource("template.md", "...")
        .build();

LlmAgent agent = LlmAgent.builder()
        .tools(new SkillToolset(skillSource))
        .build();
```

`SkillToolset` 会向 Agent 提供 Skill 相关工具。Agent 可以通过工具：

```text
列出 Skills
加载某个 Skill 的 instructions
加载某个 Skill 附带的 resource
```

## 完整代码

示例代码：

```text
src/main/java/com/hh/examples/study15/skill/SkillExample.java
```

示例中定义了两个 Skill：

```text
incident-summary：用于线上事故复盘
release-note：用于发布说明整理
```

用户输入线上事故信息时，Agent 应先发现 `incident-summary`，再加载该 Skill，并按它的结构输出复盘摘要。

## LocalSkillSource 示例

除了在 Java 代码中使用 `InMemorySkillSource` 定义 Skill，ADK 也支持从本地文件目录加载 Skill。

本章新增了本地文件版示例：

```text
src/main/java/com/hh/examples/study15/skill/LocalSkillSourceExample.java
```

本地 Skill 文件目录：

```text
src/main/java/com/hh/examples/study15/skill/skills/
```

目录结构：

```text
skills/
├── incident-summary/
│   ├── SKILL.md
│   └── references/
│       └── incident-template.md
└── release-note/
    ├── SKILL.md
    └── references/
        └── release-note-example.md
```

`LocalSkillSource` 的约定是：

```text
skills 基础目录
-> 每个子目录是一个 Skill
-> 子目录内必须包含 SKILL.md 或 skill.md
-> SKILL.md frontmatter 中的 name 必须和子目录名完全一致
-> SKILL.md 开头必须是 YAML frontmatter
-> frontmatter 后面的正文就是 Skill instructions
-> 其他文件可以作为 resource 被 load_skill_resource 按相对路径加载
-> resource 路径必须以 references/、assets/ 或 scripts/ 开头
```

因此下面的路径是合法的：

```text
references/release-note-example.md
assets/example.png
scripts/helper.py
```

下面的路径会被 `load_skill_resource` 拒绝：

```text
examples/release-note-example.md
templates/incident-template.md
```

一个最小 `SKILL.md` 示例：

```markdown
---
name: release-note
description: 将零散变更整理为发布说明。
allowed-tools: none
---

你正在使用 release-note Skill。

请按照标题、新增能力、修复问题、使用提醒的结构输出。
```

注意：本地 `SKILL.md` 使用 YAML 字段名，`allowedTools` 在文件中要写成 `allowed-tools`。Java 代码里的 `Frontmatter.builder().allowedTools(...)` 仍然使用驼峰方法名。

另外，`name` 必须使用 lowercase kebab-case，并且必须和目录名完全一致。例如目录是 `release-note/`，frontmatter 就必须写：

```yaml
name: release-note
```

不要写成 `release_note`。下划线不符合 `Frontmatter` 的命名校验，目录名和 `name` 不一致又会导致 `LocalSkillSource` 认为 Skill 文件和目录不匹配。

Java 接入方式：

```java
SkillSource skillSource = new LocalSkillSource(Path.of(
        "src/main/java/com/hh/examples/study15/skill/skills"));

LlmAgent agent = LlmAgent.builder()
        .tools(new SkillToolset(skillSource))
        .build();
```

## 运行方式

运行内存版 Skill 示例：

```powershell
mvn exec:java `
  -Dexec.mainClass="com.hh.examples.study15.skill.SkillExample"
```

运行本地文件版 Skill 示例：

```powershell
mvn exec:java `
  -Dexec.mainClass="com.hh.examples.study15.skill.LocalSkillSourceExample"
```

## 运行流程

一次典型执行流程：

```text
用户输入事故描述
-> Agent 调用 SkillToolset 提供的工具查看可用 Skill
-> Agent 加载 incident-summary Skill
-> Agent 根据 Skill instructions 生成结构化复盘
-> 控制台打印 functionCalls / functionResponses / finalResponse
```

## 可观察结果

你应该能在控制台看到类似事件：

```text
functionCalls > [list_skills ...]
functionResponses > ...
functionCalls > [load_skill ...]
functionResponses > ...
finalResponse > ...
```

最终回答应该包含：

```text
事件概述
影响范围
初步原因
处置动作
后续改进
```

## 关键源码分析

### SkillSource

`SkillSource` 是 Skill 的来源抽象。

它可以来自：

```text
内存：InMemorySkillSource
本地文件：LocalSkillSource
自定义后端：实现 SkillSource 接口
```

本章使用 `InMemorySkillSource`，方便在一个 Java 文件里演示完整概念。

### Frontmatter

`Frontmatter` 是 Skill 的摘要信息。

它帮助模型先判断：

```text
这个 Skill 叫什么
解决什么问题
是否适合当前任务
有什么元数据
```

### Instructions

`instructions` 是 Skill 的核心。

它不是立即进入 Agent 的系统提示词，而是由 Agent 通过 `load_skill` 按需加载。这样可以避免把所有技能规则都塞进主 Agent instruction。

### Resources

Resource 用来放模板、示例、参考文档等辅助材料。

当 Skill 的 instructions 不够时，Agent 可以继续通过 `load_skill_resource` 加载资源。

## 与 Tool 的区别

Tool 负责“做事”：

```text
查天气
调用接口
写文件
检索知识库
```

Skill 负责“告诉 Agent 应该怎么做”：

```text
如何写事故复盘
如何整理发布说明
如何执行某种分析流程
```

所以 Skill 更偏向动态行为规程，Tool 更偏向外部能力调用。

## ADK 为什么这样设计

ADK 将 Skill 做成 Toolset，有一个重要好处：

```text
是否加载 Skill 由 Agent 在运行时决定
```

主 Agent 只需要知道“我有一组可用 Skills”，不需要提前背下所有 Skill 内容。

这让复杂 Agent 更容易维护：

```text
主 instruction 保持稳定
Skill 可以独立增删
不同业务规程可以模块化管理
上下文窗口压力更小
```

## 思考题

1. 哪些内容适合做成 Skill，哪些内容应该继续做成 Tool？
2. 如果 Skill 数量很多，`list_skills` 是否还够用？是否需要检索式 SkillSource？
3. 在生产系统中，Skill 的版本、权限和灰度发布应该放在哪里管理？
