# PaiCli

**PaiCli** 是一个基于 Java 17 的多智能体 AI 编程助手命令行工具，支持与 OpenAI 兼容的 LLM API 交互，提供三种协作模式（单智能体、计划执行、多智能体团队），并集成了 MCP 外部工具、人机协作审批、长期记忆等能力。

## 特性

- **三种工作模式**
  - **普通模式** (`/normal`)：标准单智能体对话，循环调用工具（文件读写、Shell 命令、项目脚手架等）
  - **计划模式** (`/plan <任务>`)：LLM 将任务拆解为 DAG 子任务，按拓扑排序逐步执行，支持失败重规划
  - **团队模式** (`/team <任务>`)：5 个角色化智能体（协调者、研究员、编码者、审查者、测试者）通过消息总线与任务板自主协作，支持并行子智能体探索

- **MCP（模型上下文协议）**：连接外部 MCP 服务器（stdio / HTTP），发现并使用外部工具（如网页搜索、Firecrawl 网页抓取）

- **人机协作 (HITL)**：对危险操作（Shell 命令、文件写入、MCP 工具调用）进行交互式审批，支持工作区扩展、参数修改和"全部批准"

- **工作区安全**：`WorkspacePolicy` 限制文件系统访问范围，越界操作触发审批

- **长期记忆**：持久化记忆条目（JSON 存储），通过 BM25 结合中文分词进行相关记忆检索

- **上下文压缩**：Token 预算超限时自动基于 LLM 摘要压缩上下文

- **技能系统**：Markdown 格式的提示词补充，可从类路径和运行时目录加载

- **三层终端渲染**
  - `inline`（默认）：保留终端回滚的高级渲染，支持可折叠块、状态栏、流式输出、Markdown 渲染
  - `light`：轻量格式化输出
  - `plain`：纯文本输出

- **斜杠命令**：`/help`、`/status`、`/tools`、`/logs`、`/theme`、`/compact`、`/connect`、`/model`、`/plan`、`/team`、`/mcp`、`/skills`、`/hitl`、`/save`、`/clear`、`/exit`

- **流式 LLM 输出**：通过 SSE 实时输出 LLM 响应

- **中断支持**：`Ctrl+G` 取消当前执行

- **并行工具执行**：最多支持 4 个工具并发执行

## 环境要求

- **JDK 17** 或更高版本
- **Apache Maven 3.6** 或更高版本

## 构建

```bash
mvn clean compile       # 仅编译
mvn clean package       # 打包为带所有依赖的 fat JAR
```

## 运行

```bash
# 直接运行
mvn exec:java -Dexec.mainClass="com.raph.Main"

# 或使用打包后的 JAR
java -jar target/PaiCli-1.0-SNAPSHOT.jar
```

## 配置

PaiCli 通过环境变量或当前工作目录下的 `.env` 文件进行配置：

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `PAICLI_LLM_API_KEY` / `DEEPSEEK_API_KEY` / `OPENAI_API_KEY` / `API_KEY` | API 密钥 | - |
| `PAICLI_LLM_BASE_URL` / `OPENAI_BASE_URL` | API 基础 URL | `https://api.deepseek.com/v1` |
| `PAICLI_LLM_MODEL` / `OPENAI_MODEL` | 模型名称 | `deepseek-v4-pro` |
| `PAICLI_TUI_MODE` | 终端渲染模式：`inline`、`light`、`plain` | `inline` |
| `PAICLI_OUTPUT_TRUNCATE_LIMIT` | 输出截断行数限制 | `2000` |
| `PAICLI_LLM_CONNECT_TIMEOUT_SECONDS` | LLM 连接超时 | - |
| `PAICLI_LLM_READ_TIMEOUT_SECONDS` | LLM 读取超时 | - |
| `PAICLI_LLM_WRITE_TIMEOUT_SECONDS` | LLM 写入超时 | - |
| `PAICLI_LLM_CALL_TIMEOUT_SECONDS` | LLM 调用超时 | - |
| `PAICLI_MCP_INIT_CONCURRENCY` | MCP 初始化并发数 | - |
| `PAICLI_MCP_INIT_TIMEOUT_SECONDS` | MCP 初始化超时 | - |
| `PAICLI_SKILLS_PATH` | 额外技能目录 | - |
| `PAICLI_WORKSPACE_ROOT` | 工作区根目录 | `.` |

MCP 配置通过 `.agents/mcp.json` 文件加载，可使用系统属性 `paicli.mcp.config` 指定自定义路径。

## 启动示例

```bash
# 使用 DeepSeek
PAICLI_LLM_API_KEY=sk-your-key java -jar target/PaiCli-1.0-SNAPSHOT.jar

# 使用 OpenAI 兼容提供方
PAICLI_LLM_BASE_URL=https://api.openai.com/v1 PAICLI_LLM_API_KEY=sk-your-key PAICLI_LLM_MODEL=gpt-4o java -jar target/PaiCli-1.0-SNAPSHOT.jar

# 使用 .env 文件
echo 'PAICLI_LLM_API_KEY=sk-your-key' > .env && java -jar target/PaiCli-1.0-SNAPSHOT.jar
```

## 使用方式

进入交互会话后，直接输入自然语言描述任务，或使用斜杠命令切换模式：

- 输入 `/plan 实现一个 REST API` 进入计划模式，PaiCli 会先生成执行计划再逐步执行
- 输入 `/team 实现一个 REST API` 进入团队模式，多个角色化智能体协作完成
- 输入 `/normal` 回到普通模式

输入 `/help` 查看完整命令列表。

## 项目结构

```
src/main/java/com/raph/
├── Main.java              # 入口
├── agent/                 # 智能体系统（普通、计划、团队、子智能体）
├── cli/                   # CLI/TUI 会话、配置、命令解析
├── hitl/                  # 人机协作审批
├── interaction/           # 终端输入输出抽象（JLine / Stream）
├── llm/                   # LLM 客户端（DeepSeek / OpenAI 兼容）
├── mcp/                   # MCP 协议支持（服务器管理、JSON-RPC、传输层）
├── memory/                # 长期记忆与上下文压缩
├── plan/                  # 计划模式任务模型
├── render/                # 三层终端渲染系统
├── skill/                 # 技能加载与技能提示词构建
└── tool/                  # 工具注册表与工作区策略
```

## 测试

```bash
mvn test                          # 运行全部测试
mvn test -Dtest=<TestClassName>   # 运行指定测试类
```

## 技术栈

| 库 | 版本 | 用途 |
|---|---|---|
| Jackson | 2.16.0 | JSON 解析 |
| OkHttp | 4.12.0 | HTTP 客户端 (SSE 流式) |
| JLine | 3.25.1 | 高级终端 I/O |
| Lombok | 1.18.44 | 样板代码消除 |
| jieba-analysis | 1.0.2 | 中文分词 (记忆检索) |
| JUnit Jupiter | 5.12.2 | 测试框架 |
