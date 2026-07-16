<div align="center">

# codex-channel

面向 Codex/ChatGPT 账号的 Java 库与 CLI，提供 Chrome TLS 传输能力。

[English](README.md) | [简体中文](README.zh-CN.md)

[![Release](https://img.shields.io/github/v/release/onlyGuo/codex-channel?display_name=tag&sort=semver)](https://github.com/onlyGuo/codex-channel/releases)
[![Native release](https://img.shields.io/github/actions/workflow/status/onlyGuo/codex-channel/release-native.yml?label=native%20release)](https://github.com/onlyGuo/codex-channel/actions/workflows/release-native.yml)
[![Maven Central](https://img.shields.io/maven-central/v/ink.icoding.codex/core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/ink.icoding.codex/core)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)

</div>

`codex-channel` 包含 Chrome TLS HTTP 客户端、Java 账号客户端，以及带多账号调度和 OpenAI 兼容服务的 CLI。

> 本项目调用 ChatGPT Codex 后端接口，而不是公开的 OpenAI Platform API。上游客户端可能调整端点及行为。

## 选择模块

| 模块 | 适用场景 | 从这里开始 |
| --- | --- | --- |
| [`http`](http/README.zh-CN.md) | 需要具备 Chrome TLS 特征的 HTTP、SSE 或 WebSocket 客户端 API | [`ChromeHttpClient`](http/README.zh-CN.md#快速开始) |
| [`core`](core/README.zh-CN.md) | 从 Java 使用 OAuth、凭据、额度/模型查询、Responses 或 Chat Completions | [`ChatGptAccountClient`](core/README.zh-CN.md#快速开始) |
| [`cli`](cli/README.zh-CN.md) | 本地账号存储、调度、熔断和 OpenAI 兼容 HTTP 服务 | [`codex-channel auth login`](cli/README.zh-CN.md#快速开始) |
| [`example`](example) | 本地凭据文件的示例 | [`AuthorizationExample`](example/src/main/java/ink/icoding/codex/example/AuthorizationExample.java) |

> **只想直接使用 codex-channel？** 请从 [CLI 模块](cli/README.zh-CN.md) 开始。仅在需要将 codex-channel 集成进其他 Java 项目时，才选择 `http` 或 `core`。

## 快速开始

构建全部模块并运行测试：

```bash
./mvnw test
```

构建可执行 CLI JAR：

```bash
./mvnw -pl cli -am package
java -jar cli/target/cli-1.0.0.jar --help
```

已安装带 `native-image` 的 GraalVM JDK 时，可构建原生二进制：

```bash
./mvnw -pl cli -am -Pnative package -DskipTests
./cli/target/codex-channel --help
```

## 安装依赖

发行版坐标使用 `ink.icoding.codex` 组。将 `VERSION` 替换为 GitHub Release 版本。

```xml
<dependency>
    <groupId>ink.icoding.codex</groupId>
    <artifactId>core</artifactId>
    <version>VERSION</version>
</dependency>
```

`core` 会传递引入 HTTP 传输层；仅需要传输 API 时可直接依赖 `http`。

## 发布版本

推送 `v1.0.0` 或 `v1.0.0-rc.1` 这样的语义化标签，会构建并发布 Linux x86_64/arm64、macOS x86_64/arm64 与 Windows x86_64 原生压缩包。每个压缩包都附带 SHA-256 校验文件。

```bash
git tag v1.0.0
git push origin v1.0.0
```

Maven Central 发布与原生发布工作流分离。配置好发布凭据后，参见下方说明。

## 发布到 Maven Central

根 POM 会附加源码和 Javadoc JAR。`release` profile 会签名并通过 Central Portal 插件上传。

在 `~/.m2/settings.xml` 中配置 id 为 `central` 的 Central Portal 凭据，配置 GPG 签名后，发布非 SNAPSHOT 版本：

```bash
./mvnw -Prelease deploy
```

`example` 模块不会部署到 Central。

## 兼容性

| 平台 | HTTP/SSE 传输 | CLI 原生发行版 |
| --- | --- | --- |
| macOS x86_64 / arm64 | 首次使用自动安装 `curl-impersonate` | 支持 |
| Linux x86_64 / arm64 | 首次使用自动安装 `curl-impersonate` | 支持 |
| Windows x86_64 | 需设置兼容 CLI 的 `CURL_IMPERSONATE_BIN` | 支持 |

WebSocket 使用 JDK 兼容传输，不模拟 Chrome TLS。配置及限制见 [HTTP 模块文档](http/README.zh-CN.md)。

## 许可证

采用 [GNU GPL v3.0](LICENSE) 许可证。
