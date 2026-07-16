# codex-channel CLI

[English](README.md) | [简体中文](README.zh-CN.md)

[`cli`](../README.md) stores multiple ChatGPT/Codex accounts locally, schedules them with circuit breaking, and can run an OpenAI-compatible HTTP service.

## Build and run

```bash
./mvnw -pl cli -am package
java -jar cli/target/cli-1.0.0.jar --help
```

With GraalVM Native Image:

```bash
./mvnw -pl cli -am -Pnative package -DskipTests
./cli/target/codex-channel --help
```

All commands accept `--home /path/to/state` to choose a state directory and `--json` for machine-readable output. The default state directory is `~/.codex-channel`.

## Quick start

```bash
codex-channel auth login --alias work
codex-channel account list
codex-channel account use work
codex-channel server start
```

`auth login` prints the authorization URL and never opens a browser itself. It accepts either a local callback or a pasted complete redirect URL, so the same flow works over SSH.

## Accounts

```bash
# Import existing local credential files.
codex-channel auth import token.json account.json --alias imported

# Inspect and select accounts.
codex-channel account list
codex-channel account show work
codex-channel account use work

# Schedule accounts.
codex-channel account enable work
codex-channel account weight work 3
codex-channel account schedule --failure-threshold 3 --open-seconds 60

# Query or operate an account.
codex-channel account quota --account work
codex-channel account models --account work
codex-channel account profile --account work
codex-channel account refresh --account work
codex-channel account training disable --account work
```

Enabled accounts are selected by weighted round robin. Requests that encounter account-level failures can move to another enabled account. `server status` shows each circuit state.

## HTTP service

```bash
codex-channel server start
codex-channel server status
codex-channel server logs --lines 100
codex-channel server stop
```

The default listener is `http://127.0.0.1:8787`. For a non-loopback listener, supply an API key:

```bash
codex-channel server start --host 0.0.0.0 --port 8787 --api-key change-me
```

Endpoints:

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/health` | Health and enabled-account count |
| `GET` | `/v1/models` | Models available to the scheduled account |
| `POST` | `/v1/responses` | Supports `stream: true` SSE |
| `POST` | `/v1/chat/completions` | Supports `stream: true` SSE |

When `--api-key` is set, send it as `Authorization: Bearer <api-key>`. The `X-Codex-Account` response header identifies the account used for the request.

```bash
curl http://127.0.0.1:8787/v1/responses \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer change-me' \
  -d '{"model":"gpt-5.2-codex","input":"hello"}'
```

## Transport setup

```bash
codex-channel transport doctor
codex-channel transport install
```

macOS and Linux install the required `curl-impersonate` transport automatically when needed. On Windows, set `CURL_IMPERSONATE_BIN` to a compatible CLI before making upstream HTTP/SSE requests.

## Stored files

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

On POSIX systems, account directories use owner-only permissions; tokens, account files, and service control data are stored as owner-readable and owner-writable files.

## Related modules

- [`core`](../core/README.md) is the Java account API used by the CLI.
- [`http`](../http/README.md) is the Chrome TLS transport used for upstream HTTP and SSE.
