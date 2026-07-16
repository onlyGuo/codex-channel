# codex-channel CLI

[English](README.md) | [简体中文](README.zh-CN.md)

[`cli`](../README.md) manages local ChatGPT/Codex accounts, schedules enabled accounts with circuit breaking, and exposes an OpenAI-compatible HTTP service. It never opens a browser itself.

## Install and run

Build the executable JAR:

```bash
./mvnw -pl cli -am package
java -jar cli/target/cli-1.0.0.jar --help
```

With GraalVM Native Image:

```bash
./mvnw -pl cli -am -Pnative package -DskipTests
./cli/target/codex-channel --help
```

The examples below use `codex-channel` for either executable.

## Command tree

```text
codex-channel [GLOBAL OPTIONS] <command> [command options]
├── auth
│   ├── login [--alias ALIAS] [--manual] [--timeout-seconds SECONDS] [--force]
│   └── import TOKEN_FILE ACCOUNT_FILE [--alias ALIAS] [--force]
├── account (alias: accounts)
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

Run `codex-channel <command> --help` for the generated help of a branch or leaf command. `server run` is an internal process entry point and is intentionally not a public command.

## Global options

Place global options before the command for portable shell usage.

| Option | Meaning |
| --- | --- |
| `--home PATH` | State directory. Defaults to `~/.codex-channel`. Use this to isolate accounts, service state, and logs. |
| `--json` | Emit structured result values as pretty-printed JSON. Plain text remains in use for prompts, usage, and log lines. |
| `--debug` | Print a stack trace when a command fails. |
| `-h`, `--help` | Show help for the selected command. |
| `-V`, `--version` | Print the CLI version. |

Account aliases must match `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`. When an alias is omitted for a remote account operation, the CLI uses the active account; if there is no active account but exactly one account exists, it uses that account. Otherwise, select one with `account use` or provide `--account`.

## `auth`: add accounts

### `auth login`

```text
codex-channel auth login [--alias ALIAS] [--manual]
                         [--timeout-seconds SECONDS] [--force]
```

Starts an OAuth login, prints the authorization URL, exchanges the callback code, then stores the token and account data. The CLI does not launch a browser.

| Option | Meaning |
| --- | --- |
| `--alias ALIAS` | Name under which to save the account. If omitted, an alias is derived from the account identity and made unique. |
| `--manual` | Do not start the local callback listener. Paste the complete redirected URL in the terminal. |
| `--timeout-seconds SECONDS` | Maximum time to wait for a callback or pasted URL. Default: `600`. |
| `--force` | Replace an existing account with the same alias. |

Without `--manual`, the command listens on its OAuth callback address and also accepts a pasted redirect URL. This supports SSH, remote hosts, blocked callback ports, or a browser running on another machine. The pasted URL must be the complete final redirect URL, including `code` and `state`.

### `auth import`

```text
codex-channel auth import TOKEN_FILE ACCOUNT_FILE [--alias ALIAS] [--force]
```

Imports existing `token.json` and `account.json` files without performing OAuth. `--alias` and `--force` have the same behavior as `auth login`.

## `account`: inspect and schedule accounts

### Local account commands

| Command                            | Parameters and behavior |
|------------------------------------| --- |
| `account list`                     | Lists stored accounts, active marker, enabled state, weight, plan, and email. |
| `account show [ALIAS]`             | Shows non-secret metadata for `ALIAS`, or the selected account when omitted. Token values are never displayed. |
| `account use ALIAS`                | Makes `ALIAS` the default for operations without `--account`. |
| `account enable ALIAS`             | Allows `ALIAS` to receive scheduled service requests. |
| `account disable ALIAS`            | Removes `ALIAS` from the scheduler without deleting it. |
| `account weight ALIAS WEIGHT`      | Sets weighted round-robin preference. `WEIGHT` must be from `1` to `100`. |
| `account remove ALIAS [-y\|--yes]` | Deletes local credentials, cookies, and metadata. Prompts for confirmation unless `-y` or `--yes` is given. If it was active, another remaining account becomes active. |

### Scheduler and circuit breaker

```text
codex-channel account schedule [--failure-threshold COUNT] [--open-seconds SECONDS]
```

Without options, this prints the current policy. With either option, it updates the persisted configuration and prints the result.

| Option | Meaning |
| --- | --- |
| `--failure-threshold COUNT` | Consecutive account-level failures before its circuit opens. Range: `1`-`100`; default: `3`. |
| `--open-seconds SECONDS` | How long an open circuit is excluded before a half-open probe. Range: `1`-`86400`; default: `60`. |

The only policy is `weighted-round-robin`: enabled accounts are preferred in proportion to their weight. On account-level request failures, another eligible account may be tried. An account whose circuit is open is skipped until its recovery window, then a successful probe closes the circuit.

### Remote account commands

Each command below accepts `--account ALIAS`; see the global account-selection rule when it is omitted. These commands contact the upstream service and may refresh persisted credentials.

| Command                                                | Result |
|--------------------------------------------------------| --- |
| `account refresh [--account ALIAS]`                    | Refreshes the selected account token and reports its lifetime. |
| `account quota [--account ALIAS]`                      | Returns five-hour and weekly quota information. |
| `account models [--account ALIAS]`                     | Lists models currently available to the account. |
| `account profile [--account ALIAS]`                    | Returns the remote Codex profile. |
| `account usage [--account ALIAS]`                      | Returns the raw usage and rate-limit payload. |
| `account training <enable\|disable> [--account ALIAS]` | Changes the account training setting. `on`/`true` and `off`/`false` are also accepted. |
| `account reset-credits [--account ALIAS]`              | Lists available rate-limit reset credits. |

## `server`: run the HTTP service

### `server start`

```text
codex-channel server start [--host HOST] [--port PORT]
                           [--api-key KEY] [--foreground]
```

Starts the OpenAI-compatible HTTP service. By default it starts in the background, writes output to `logs/server.log`, and stores a local control record in `service.json`.

| Option | Meaning |
| --- | --- |
| `--host HOST` | Listener host. Default: `127.0.0.1`. |
| `--port PORT` | Listener port from `0` to `65535`. Default: `8787`; `0` asks the OS for a free port. |
| `--api-key KEY` | Bearer token required for public API endpoints. Required for a non-loopback host. |
| `--foreground` | Keep the service attached to the current terminal instead of spawning a background process. |

Bindings other than `localhost`, `127.0.0.1`, or `::1` require `--api-key`. Do not expose the service publicly without additional network controls.

### Service control commands

| Command                           | Behavior |
|-----------------------------------| --- |
| `server status`                   | Returns service status, PID, URL, start time, and scheduler/circuit state. Exits with code `1` when the service is stopped. |
| `server stop`                     | Stops a running background service through its local control token. It is a successful no-op when no service is running. |
| `server logs [-n\|--lines COUNT]` | Prints the most recent log lines. Default: `50`; range: `1`-`10000`. |

### Public HTTP API

The default base URL is `http://127.0.0.1:8787`. When `--api-key` is configured, all endpoints except `/health` require `Authorization: Bearer <KEY>`. A successful scheduled request includes `X-Codex-Account` with the selected alias.

| Method | Path | Behavior |
| --- | --- | --- |
| `GET` | `/health` | Returns service health and the number of schedulable accounts. |
| `GET` | `/v1/models` | Returns models for a scheduled account. |
| `POST` | `/v1/responses` | Proxies a Responses request. Set JSON `stream` to `true` for SSE. |
| `POST` | `/v1/chat/completions` | Proxies a Chat Completions request. Set JSON `stream` to `true` for SSE. |

Request bodies must be JSON objects and are limited to 16 MiB. Upstream account failures are returned as OpenAI-style error objects; no eligible account results in `503`.

```bash
codex-channel server start --host 0.0.0.0 --api-key change-me

curl http://127.0.0.1:8787/v1/responses \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer change-me' \
  -d '{"model":"gpt-5.6-sol","input":[{"role":"user","content":[{"type":"text_input","text":"Hello"}]}]}'
```

## `transport`: prepare Chrome TLS

| Command | Behavior |
| --- | --- |
| `transport doctor` | Resolves `curl-impersonate`, reports OS, architecture, executable path, and HTTP/SSE/WebSocket capabilities. Exits with an error when the transport cannot be resolved. |
| `transport install` | Resolves or installs the pinned `curl-impersonate` executable, then reports its path. |

macOS and Linux provision the supported binary automatically when necessary. On Windows, set `CURL_IMPERSONATE_BIN` to a compatible CLI before upstream HTTP or SSE requests. See the [HTTP module](../http/README.md) for all transport environment variables.

## Local state and security

```text
~/.codex-channel/
├── config.json                 # active account and scheduler policy
├── accounts/<alias>/
│   ├── token.json              # sensitive
│   ├── account.json            # sensitive account identity data
│   ├── metadata.json           # enabled state and weight
│   └── cookies.txt             # upstream cookies
├── service.json                # local service control token and process state
└── logs/server.log
```

On POSIX systems, state and account directories are owner-only and sensitive files are owner-readable/writable. Do not commit this directory or share its token files.

## Related modules

- [`core`](../core/README.md) is the Java account API used by the CLI.
- [`http`](../http/README.md) is the Chrome TLS transport used for upstream HTTP and SSE.
