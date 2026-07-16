# codex-channel CLI

[English](README.md) | [简体中文](README.zh-CN.md)

[`cli`](../README.zh-CN.md) 在本地保存多个 ChatGPT/Codex 账号，通过熔断调度账号，并可运行 OpenAI 兼容 HTTP 服务。

## 构建与运行

```bash
./mvnw -pl cli -am package
java -jar cli/target/cli-1.0.0.jar --help
```

使用 GraalVM Native Image：

```bash
./mvnw -pl cli -am -Pnative package -DskipTests
./cli/target/codex-channel --help
```

所有命令都支持 `--home /path/to/state` 指定状态目录，支持 `--json` 输出机器可读结果。默认状态目录是 `~/.codex-channel`。

## 快速开始

```bash
codex-channel auth login --alias work
codex-channel account list
codex-channel account use work
codex-channel server start
```

`auth login` 只打印授权 URL，不会自行打开浏览器。它既支持本地回调，也支持粘贴完整重定向 URL，因此可以在 SSH 环境中使用。

## 账号管理

```bash
# 导入已有的本地凭据文件。
codex-channel auth import token.json account.json --alias imported

# 查看并选择账号。
codex-channel account list
codex-channel account show work
codex-channel account use work

# 调度账号。
codex-channel account enable work
codex-channel account weight work 3
codex-channel account schedule --failure-threshold 3 --open-seconds 60

# 查询或操作账号。
codex-channel account quota --account work
codex-channel account models --account work
codex-channel account profile --account work
codex-channel account refresh --account work
codex-channel account training disable --account work
```

已启用账号采用加权轮询选择。遇到账号级失败时，请求可切换到其他已启用账号。`server status` 会显示每个账号的熔断状态。

## HTTP 服务

```bash
codex-channel server start
codex-channel server status
codex-channel server logs --lines 100
codex-channel server stop
```

默认监听地址为 `http://127.0.0.1:8787`。监听非回环地址时应设置 API key：

```bash
codex-channel server start --host 0.0.0.0 --port 8787 --api-key change-me
```

端点：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/health` | 健康状态与已启用账号数 |
| `GET` | `/v1/models` | 当前调度账号可用的模型 |
| `POST` | `/v1/responses` | 支持 `stream: true` SSE |
| `POST` | `/v1/chat/completions` | 支持 `stream: true` SSE |

设置 `--api-key` 后，使用 `Authorization: Bearer <api-key>` 请求。`X-Codex-Account` 响应头标识本次请求所使用的账号。

```bash
curl http://127.0.0.1:8787/v1/responses \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer change-me' \
  -d '{"model":"gpt-5.2-codex","input":"hello"}'
```

## 传输配置

```bash
codex-channel transport doctor
codex-channel transport install
```

macOS 和 Linux 在需要时自动安装 `curl-impersonate` 传输。Windows 发起上游 HTTP/SSE 请求前，需要将 `CURL_IMPERSONATE_BIN` 设为兼容的 CLI。

## 存储文件

```text
~/.codex-channel/
├── config.json
├── accounts/<alias>/
│   ├── token.json
│   ├── account.json
│   ├── metadata.json
│   └── cookies.txt
├── service.json
└── logs/server.log
```

在 POSIX 系统中，账号目录仅允许所有者访问；令牌、账号文件和服务控制数据均保存为仅所有者可读写的文件。

## 相关模块

- [`core`](../core/README.zh-CN.md) 是 CLI 使用的 Java 账号 API。
- [`http`](../http/README.zh-CN.md) 是上游 HTTP 与 SSE 使用的 Chrome TLS 传输层。
