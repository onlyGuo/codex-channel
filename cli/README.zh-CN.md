# codex-channel CLI

[English](README.md) | [简体中文](README.zh-CN.md)

[`cli`](../README.zh-CN.md) 用于管理本地 ChatGPT/Codex 账号，以熔断机制调度已启用账号，并提供 OpenAI 兼容 HTTP 服务。CLI 不会自行打开浏览器。

## 安装与运行

构建可执行 JAR：

```bash
./mvnw -pl cli -am package
java -jar cli/target/cli-1.0.0.jar --help
```

使用 GraalVM Native Image：

```bash
./mvnw -pl cli -am -Pnative package -DskipTests
./cli/target/codex-channel --help
```

下文的 `codex-channel` 指代以上任一可执行形式。

## 命令树

```text
codex-channel [全局参数] <命令> [命令参数]
├── auth
│   ├── login [--alias ALIAS] [--manual] [--timeout-seconds SECONDS] [--force]
│   └── import TOKEN_FILE ACCOUNT_FILE [--alias ALIAS] [--force]
├── account（别名：accounts）
│   ├── list
│   ├── show [ALIAS]
│   ├── use ALIAS
│   ├── enable ALIAS
│   ├── disable ALIAS
│   ├── weight ALIAS WEIGHT
│   ├── schedule [--failure-threshold COUNT] [--open-seconds SECONDS]
│   ├── remove ALIAS [-y|--yes]
│   ├── refresh [--account ALIAS]
│   ├── quota [--account ALIAS]
│   ├── models [--account ALIAS]
│   ├── profile [--account ALIAS]
│   ├── usage [--account ALIAS]
│   ├── training <enable|disable> [--account ALIAS]
│   └── reset-credits [--account ALIAS]
├── server
│   ├── start [--host HOST] [--port PORT] [--api-key KEY] [--foreground]
│   ├── stop
│   ├── status
│   └── logs [-n|--lines COUNT]
└── transport
    ├── doctor
    └── install
```

使用 `codex-channel <命令> --help` 查看任意分支或叶子命令生成的帮助。`server run` 是内部进程入口，不是面向用户的命令。

## 全局参数

为获得一致的 Shell 行为，请将全局参数置于命令之前。

| 参数 | 含义 |
| --- | --- |
| `--home PATH` | 状态目录，默认 `~/.codex-channel`。用于隔离账号、服务状态和日志。 |
| `--json` | 将结构化结果输出为格式化 JSON；提示信息、帮助和日志仍为纯文本。 |
| `--debug` | 命令失败时打印异常堆栈。 |
| `-h`、`--help` | 显示所选命令的帮助。 |
| `-V`、`--version` | 输出 CLI 版本。 |

账号别名必须匹配 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`。远程账号命令未指定别名时，CLI 使用当前活动账号；若没有活动账号但仅保存了一个账号，则使用该账号；其余情况必须先执行 `account use` 或传入 `--account`。

## `auth`：添加账号

### `auth login`

```text
codex-channel auth login [--alias ALIAS] [--manual]
                         [--timeout-seconds SECONDS] [--force]
```

启动 OAuth 登录，打印授权 URL，交换回调 code，并保存 Token 与账号数据。CLI 不会启动浏览器。

| 参数 | 含义 |
| --- | --- |
| `--alias ALIAS` | 保存账号的名称。不指定时根据账号身份生成唯一别名。 |
| `--manual` | 不启动本地回调监听器，必须在终端粘贴完整重定向 URL。 |
| `--timeout-seconds SECONDS` | 等待回调或粘贴 URL 的最长时间，默认 `600`。 |
| `--force` | 替换同名的已有账号。 |

未指定 `--manual` 时，命令会监听 OAuth 本地回调，同时允许粘贴重定向 URL。因此 SSH、远程主机、端口无法回连或浏览器位于另一机器时均可登录。粘贴内容必须是最终完整跳转 URL，且包含 `code` 和 `state`。

### `auth import`

```text
codex-channel auth import TOKEN_FILE ACCOUNT_FILE [--alias ALIAS] [--force]
```

导入已有的 `token.json` 与 `account.json`，不执行 OAuth。`--alias` 和 `--force` 的语义与 `auth login` 相同。

## `account`：查询和调度账号

### 本地账号命令

| 命令                            | 参数与行为 |
|-------------------------------| --- |
| `account list`                | 列出保存的账号、活动标记、启用状态、权重、套餐和邮箱。 |
| `account show [ALIAS]`        | 显示 `ALIAS` 的非敏感元数据；不传时显示当前选中的账号。不会输出 Token。 |
| `account use ALIAS`           | 将 `ALIAS` 设为未传 `--account` 时使用的默认账号。 |
| `account enable ALIAS`        | 允许 `ALIAS` 接收调度后的服务请求。 |
| `account disable ALIAS`       | 将 `ALIAS` 移出调度器，但不删除账号。 |
| `account weight ALIAS WEIGHT` | 设置加权轮询优先级，`WEIGHT` 取值必须为 `1`-`100`。 |
| `account remove ALIAS [-y\|--yes]` | 删除本地凭据、Cookie 和元数据。未传 `-y`/`--yes` 时要求确认；若删除的是活动账号，则自动选择其余账号之一。 |

### 调度与熔断

```text
codex-channel account schedule [--failure-threshold COUNT] [--open-seconds SECONDS]
```

不传参数时输出当前策略；传入任一参数时会更新持久化配置并输出更新后的结果。

| 参数 | 含义 |
| --- | --- |
| `--failure-threshold COUNT` | 单个账号连续失败多少次后打开熔断器，范围 `1`-`100`，默认 `3`。 |
| `--open-seconds SECONDS` | 打开熔断器后排除多久，再允许半开探测，范围 `1`-`86400`，默认 `60`。 |

当前仅支持 `weighted-round-robin` 策略：已启用账号按权重比例获得请求。账号级请求失败时可尝试其他可用账号；熔断器打开的账号会在恢复窗口结束前被跳过，探测成功后恢复闭合状态。

### 远程账号命令

下列命令均支持 `--account ALIAS`，未传时按全局账号选择规则处理。它们会访问上游服务，并可能刷新已保存的凭据。

| 命令                                                     | 结果 |
|--------------------------------------------------------| --- |
| `account refresh [--account ALIAS]`                    | 刷新所选账号的 Token，并输出有效期。 |
| `account quota [--account ALIAS]`                      | 返回 5 小时和周额度信息。 |
| `account models [--account ALIAS]`                     | 列出账号当前可用的模型。 |
| `account profile [--account ALIAS]`                    | 返回远端 Codex 个人资料。 |
| `account usage [--account ALIAS]`                      | 返回原始使用量和限流数据。 |
| `account training <enable\|disable> [--account ALIAS]` | 修改账号训练设置，也接受 `on`/`true` 和 `off`/`false`。 |
| `account reset-credits [--account ALIAS]`              | 列出可用的限流额度重置 credits。 |

## `server`：运行 HTTP 服务

### `server start`

```text
codex-channel server start [--host HOST] [--port PORT]
                           [--api-key KEY] [--foreground]
```

启动 OpenAI 兼容 HTTP 服务。默认后台运行，输出写入 `logs/server.log`，本地控制记录写入 `service.json`。

| 参数 | 含义 |
| --- | --- |
| `--host HOST` | 监听主机，默认 `127.0.0.1`。 |
| `--port PORT` | 监听端口，范围 `0`-`65535`，默认 `8787`；使用 `0` 时由操作系统分配空闲端口。 |
| `--api-key KEY` | 访问公开 API 所需的 Bearer Token。非环回地址必须设置。 |
| `--foreground` | 保持服务附着在当前终端，而不是创建后台进程。 |

监听地址不是 `localhost`、`127.0.0.1` 或 `::1` 时，必须指定 `--api-key`。将服务暴露到公网前，还应配置额外的网络访问控制。

### 服务控制命令

| 命令                                | 行为 |
|-----------------------------------| --- |
| `server status`                   | 返回服务状态、PID、URL、启动时间与调度/熔断状态。服务未运行时退出码为 `1`。 |
| `server stop`                     | 使用本地控制 Token 停止后台服务。服务未运行时为成功的无操作。 |
| `server logs [-n\|--lines COUNT]` | 输出最近日志行数，默认 `50`，范围 `1`-`10000`。 |

### 公开 HTTP API

默认基础地址为 `http://127.0.0.1:8787`。设置 `--api-key` 后，除 `/health` 外的所有端点都需要 `Authorization: Bearer <KEY>`。调度成功的请求会在 `X-Codex-Account` 响应头中返回实际账号别名。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `GET` | `/health` | 返回服务健康状态和可调度账号数。 |
| `GET` | `/v1/models` | 返回调度账号的模型列表。 |
| `POST` | `/v1/responses` | 转发 Responses 请求；JSON 中的 `stream: true` 启用 SSE。 |
| `POST` | `/v1/chat/completions` | 转发 Chat Completions 请求；JSON 中的 `stream: true` 启用 SSE。 |

请求体必须为 JSON 对象，最大 16 MiB。上游账号错误以 OpenAI 风格错误对象返回；没有可用账号时返回 `503`。

```bash
codex-channel server start --host 0.0.0.0 --api-key change-me

curl http://127.0.0.1:8787/v1/responses \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer change-me' \
  -d '{"model":"gpt-5.6-sol","input":[{"role":"user","content":[{"type":"text_input","text":"Hello"}]}]}'
```

## `transport`：准备 Chrome TLS 传输

| 命令 | 行为 |
| --- | --- |
| `transport doctor` | 解析 `curl-impersonate`，输出操作系统、架构、可执行路径和 HTTP/SSE/WebSocket 能力。无法解析传输程序时以错误退出。 |
| `transport install` | 解析或安装固定版本的 `curl-impersonate`，并输出其路径。 |

macOS 和 Linux 在需要时会自动准备支持的二进制。Windows 发起上游 HTTP 或 SSE 请求前，必须将 `CURL_IMPERSONATE_BIN` 设为兼容 CLI。完整环境变量参见 [HTTP 模块](../http/README.zh-CN.md)。

## 本地状态与安全

```text
~/.codex-channel/
├── config.json                 # 活动账号和调度策略
├── accounts/<alias>/
│   ├── token.json              # 敏感信息
│   ├── account.json            # 敏感账号身份信息
│   ├── metadata.json           # 启用状态和权重
│   └── cookies.txt             # 上游 Cookie
├── service.json                # 本地服务控制 Token 和进程状态
└── logs/server.log
```

在 POSIX 系统中，状态及账号目录仅允许所有者访问，敏感文件仅允许所有者读写。请勿提交该目录或共享其中的 Token 文件。

## 相关模块

- [`core`](../core/README.zh-CN.md) 是 CLI 使用的 Java 账号 API。
- [`http`](../http/README.zh-CN.md) 是上游 HTTP 与 SSE 使用的 Chrome TLS 传输层。
