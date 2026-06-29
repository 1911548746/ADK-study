# MCP 集成坑点记录

## 背景

本记录来自第 13 章 MCP 示例中，把 ADK `McpToolset` 接入 `chrome-devtools-mcp@latest` 时遇到的两个运行问题。

示例调用链：

```text
LlmAgent
  -> McpToolset
  -> StdioClientTransport
  -> chrome-devtools-mcp
  -> Chrome / DevTools
```

目标是让 Agent 通过 MCP 工具真实操作 Chrome 浏览器，而不是模拟浏览器结果。

## 坑点一：Windows 下 ProcessBuilder 找不到 npx

### 现象

控制台报错：

```text
Failed to start process with command:
[npx, -y, chrome-devtools-mcp@latest, ...]

Cannot run program "npx": CreateProcess error=2, 系统找不到指定的文件。
```

### 原因

ADK MCP 的 stdio 模式底层会通过 Java `ProcessBuilder` 启动 MCP Server。

在 Windows 上，`npx` 通常不是一个可被 `ProcessBuilder` 直接执行的原生可执行文件，而是通过 `cmd` 或 `npx.cmd` 间接启动。

Shell 里能运行：

```powershell
npx -y chrome-devtools-mcp@latest
```

不代表 Java `ProcessBuilder` 一定能直接运行：

```java
new ProcessBuilder("npx", "-y", "chrome-devtools-mcp@latest")
```

因为 `ProcessBuilder` 不等价于 PowerShell 或 cmd。

### 修复方式

Windows 下使用官方建议：

```java
StdioServerParameters stdioServer = StdioServerParameters.builder()
        .command("cmd")
        .args(List.of("/c", "npx", "-y", "chrome-devtools-mcp@latest"))
        .env(Map.of())
        .build();
```

跨平台写法：

```java
private static String chromeDevToolsMcpCommand() {
    String osName = System.getProperty("os.name", "").toLowerCase();
    return osName.contains("win") ? "cmd" : "npx";
}

private static List<String> chromeDevToolsMcpArgs() {
    List<String> args = new ArrayList<>();
    String osName = System.getProperty("os.name", "").toLowerCase();
    if (osName.contains("win")) {
        args.add("/c");
        args.add("npx");
    }
    args.add("-y");
    args.add("chrome-devtools-mcp@latest");
    return args;
}
```

### 额外注意

`chrome-devtools-mcp` 不需要文件系统 workspace 参数。

下面这种参数来自 filesystem MCP，不适合 Chrome DevTools MCP：

```java
args(List.of("-y", "chrome-devtools-mcp@latest", workspace.toString()))
```

Chrome DevTools MCP 的常用参数是：

```text
--headless=true
--isolated=true
--browserUrl=http://127.0.0.1:9222
--channel=stable
--no-usage-statistics
--no-performance-crux
```

## 坑点二：MCP Server 启动成功后 Jackson NoSuchMethodError

### 现象

修改 Windows 启动命令后，MCP Server 已经能启动：

```text
MCP server starting.
MCP server started
```

但随后报错：

```text
java.lang.NoSuchMethodError:
'com.fasterxml.jackson.annotation.OptBoolean
 com.fasterxml.jackson.annotation.JsonProperty.isRequired()'
```

堆栈关键位置：

```text
tools.jackson.databind.ObjectMapper.writeValueAsString(...)
io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper.writeValueAsString(...)
io.modelcontextprotocol.client.transport.StdioClientTransport...
```

### 原因

ADK MCP 依赖 MCP Java SDK。

MCP Java SDK 中的 JSON 实现使用 Jackson 3：

```text
io.modelcontextprotocol.sdk:mcp-json-jackson3
  -> tools.jackson.core:jackson-databind:3.0.3
```

但 Jackson 3 的 databind 仍然依赖这个 annotations 坐标：

```text
com.fasterxml.jackson.core:jackson-annotations
```

而本项目引入了 Spring Boot：

```xml
<dependencyManagement>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
    </dependency>
</dependencyManagement>
```

Spring Boot BOM 会管理常见依赖版本，其中包括 Jackson 2 生态。

在本项目中，`spring-boot-dependencies 3.4.2` 管理到的 `jackson-annotations` 是：

```text
jackson-annotations 2.18.2
```

这个版本缺少 Jackson 3 databind 运行时需要的：

```java
JsonProperty.isRequired()
```

于是出现 `NoSuchMethodError`。

### 为什么编译能过，运行才报错

这是典型的运行时 classpath 版本冲突。

编译阶段只要类型能解析，未必会触发 MCP SDK 内部序列化逻辑。

真正启动 MCP stdio 通道后，SDK 要把初始化请求写给 MCP Server：

```text
MCP Client -> JSON serialize -> stdio -> MCP Server
```

这时 Jackson 3 databind 调用了新版 annotations API，但运行时 classpath 上实际加载的是旧版 `jackson-annotations`，所以才在运行阶段炸。

### 修复方式

在项目 `pom.xml` 中显式提升 `jackson-annotations`：

```xml
<properties>
    <jackson-annotations.version>2.20</jackson-annotations.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-annotations</artifactId>
        <version>${jackson-annotations.version}</version>
    </dependency>
</dependencies>
```

这会覆盖 Spring Boot BOM 对该 artifact 的默认版本。

### 验证方式

编译：

```powershell
mvn -q -DskipTests compile
```

确认 classpath 里选中的是新版本：

```powershell
mvn -q dependency:build-classpath "-Dmdep.outputFile=target\classpath.txt"
Select-String -Path target\classpath.txt -Pattern "jackson-annotations-[^;]+\.jar" -AllMatches |
  ForEach-Object { $_.Matches.Value }
```

期望看到：

```text
jackson-annotations-2.20.jar
```

如果命令行已经正确，但 IDE 仍报同样错误，通常是 IDE 运行配置还在使用旧 classpath。

处理方式：

```text
Reload Maven Project
重新创建 Run Configuration
Invalidate Caches / Restart
```

## 依赖关系总结

这次冲突可以简化为：

```text
Spring Boot 3.4.2 BOM
  -> 管理 jackson-annotations = 2.18.2

ADK 1.4.0 MCP
  -> MCP Java SDK 1.1.2
  -> mcp-json-jackson3
  -> tools.jackson.databind 3.0.3
  -> 运行时需要更新的 jackson-annotations API
```

冲突点不是 Spring MVC 的 Jackson 2 `ObjectMapper` 和 MCP SDK 的 Jackson 3 `ObjectMapper` 本身。

真正冲突点是两套体系共用了：

```text
com.fasterxml.jackson.core:jackson-annotations
```

## 排查顺序

遇到 MCP stdio 启动失败时，按这个顺序排查：

1. MCP Server 是否能在命令行直接启动：

```powershell
cmd /c npx -y chrome-devtools-mcp@latest --help
```

2. Java 启动命令是否适配 Windows：

```text
command = cmd
args = /c npx -y chrome-devtools-mcp@latest
```

3. MCP Server 是否真的 started：

```text
MCP server started
```

4. 如果 started 后报 `NoSuchMethodError`，优先查 classpath 版本冲突。

5. 用 `dependency:build-classpath` 确认实际加载的 jar，而不是只看 `pom.xml`。

## 经验结论

MCP 集成里有两类问题很容易混在一起：

```text
启动问题：MCP Server 子进程没起来。
协议问题：Server 起来了，但 client/server 初始化或工具调用失败。
```

这次两个问题分别对应：

```text
Cannot run program "npx"
  -> 子进程启动问题

JsonProperty.isRequired() NoSuchMethodError
  -> Java 依赖版本冲突问题
```

先判断 MCP Server 有没有成功启动，再判断是不是协议初始化、JSON 序列化、工具加载或模型调用阶段的问题。这样排查会清楚很多。
