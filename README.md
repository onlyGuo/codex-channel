<div align="center">

# codex-channel

Java libraries and a CLI for using Codex/ChatGPT accounts through a Chrome-TLS transport.

[English](README.md) | [简体中文](README.zh-CN.md)

[![Release](https://img.shields.io/github/v/release/onlyGuo/codex-channel?display_name=tag&sort=semver)](https://github.com/onlyGuo/codex-channel/releases)
[![Native release](https://img.shields.io/github/actions/workflow/status/onlyGuo/codex-channel/release-native.yml?label=native%20release)](https://github.com/onlyGuo/codex-channel/actions/workflows/release-native.yml)
[![Maven Central](https://img.shields.io/maven-central/v/ink.icoding.codex/core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/ink.icoding.codex/core)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)

</div>

`codex-channel` provides three usable surfaces: a Chrome TLS HTTP client, a Java account client, and a multi-account CLI with an OpenAI-compatible HTTP service.

> This project talks to ChatGPT Codex backend endpoints rather than the public OpenAI Platform API. Endpoint availability and behavior can change with the upstream client.

## Choose a module

| Module | Use it when you need | Start here |
| --- | --- | --- |
| [`http`](http/README.md) | HTTP, SSE, or WebSocket client APIs with Chrome TLS impersonation for HTTP/SSE | [`ChromeHttpClient`](http/README.md#quick-start) |
| [`core`](core/README.md) | OAuth, credentials, quota/model queries, Responses, or Chat Completions from Java | [`ChatGptAccountClient`](core/README.md#quick-start) |
| [`cli`](cli/README.md) | Local account storage, scheduling, circuit breaking, and an OpenAI-compatible service | [`codex-channel auth login`](cli/README.md#quick-start) |
| [`example`](example) | A local credential-file example | [`AuthorizationExample`](example/src/main/java/ink/icoding/codex/example/AuthorizationExample.java) |

> **Want to use codex-channel directly?** Start with the [CLI module](cli/README.md). Use `http` or `core` only when you are integrating codex-channel into another Java project.

## Quick start

Build all modules and run the test suite:

```bash
./mvnw test
```

Build the executable CLI JAR:

```bash
./mvnw -pl cli -am package
java -jar cli/target/cli-1.0.0.jar --help
```

Use the native binary when a GraalVM JDK with `native-image` is available:

```bash
./mvnw -pl cli -am -Pnative package -DskipTests
./cli/target/codex-channel --help
```

## Installation

Released artifacts are published under the `ink.icoding.codex` group. Replace `VERSION` with a GitHub Release version.

```xml
<dependency>
    <groupId>ink.icoding.codex</groupId>
    <artifactId>core</artifactId>
    <version>VERSION</version>
</dependency>
```

`core` brings in the HTTP transport transitively. Add `http` directly when only the transport API is needed.

## Releases

Pushing a semantic tag such as `v1.0.0` or `v1.0.0-rc.1` builds and publishes native archives for Linux x86_64/arm64, macOS x86_64/arm64, and Windows x86_64. Every archive has a SHA-256 sidecar file.

```bash
git tag v1.0.0
git push origin v1.0.0
```

Maven Central publishing is intentionally separate from the native-release workflow. See [Publishing to Maven Central](#publishing-to-maven-central) when release credentials are configured.

## Publishing to Maven Central

The root POM attaches source and Javadoc JARs. The `release` profile signs artifacts and sends the bundle through the Central Portal plugin using Maven server id `central`.

Add Central Portal credentials to `~/.m2/settings.xml`, configure GPG signing, then publish a non-SNAPSHOT version:

```bash
./mvnw -Prelease deploy
```

The `example` module is excluded from deployment. See the module POMs for the published artifacts.

## Compatibility

| Platform | HTTP/SSE transport | CLI native release |
| --- | --- | --- |
| macOS x86_64 / arm64 | Auto-installs `curl-impersonate` on first use | Yes |
| Linux x86_64 / arm64 | Auto-installs `curl-impersonate` on first use | Yes |
| Windows x86_64 | Set `CURL_IMPERSONATE_BIN` to a compatible CLI | Yes |

WebSocket uses the JDK compatibility transport and does not impersonate Chrome TLS. Details and configuration are in the [HTTP module guide](http/README.md).

## License

Licensed under [GNU GPL v3.0](LICENSE).
