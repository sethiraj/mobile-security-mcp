# MobSF MCP Server: Milestones

Spring AI MCP server for MobSF. It runs over stdio or Streamable HTTP (HTTP by default) and ships as a public Docker image under the MIT license.
Each milestone produces something you can run and has exit criteria you can check. Size: S ≈ 2–3 days, M ≈ 1 week, L ≈ 2 weeks.

| # | Milestone | Size | Depends on | Release tag | Status |
|---|---|---|---|---|---|
| M0 | Foundations & validation | S | none | none | ✅ done (still open: market re-check, final group ID and image namespace) |
| M1 | Core server + dual transport | M | M0 | `v0.1.0-alpha` | ✅ done (verified against the real MobSF in M3) |
| M2 | Static analysis tools | M | M1 | none | ✅ done |
| M3 | Docker image + compose test harness (official MobSF) | M | M2 | `v0.2.0` (preview image) | ✅ done locally (GHCR push is part of M7) |
| M4 | JSON reports (normalized v1 schema) | M | M2 | `v0.3.0` | ✅ done |
| M5 | HTTP hardening + remote uploads | S | M3 | `v0.4.0` | ✅ done |
| M6 | Dynamic analysis, device & Frida | L | M4 | `v0.5.0` | ⚠️ code + docs done; awaiting a live device run (no emulator on this machine) |
| M7 | Public release prep | M | M5, M6 | `v1.0.0-beta.1` | ⚠️ everything prepared and version set; the push, GitHub repo and registry listings need your account |
| M8 | Extras (optional) | M | M7 | `v1.1.0` | ⚠️ SARIF + GitHub Action done and verified; native image documented but unbuilt; sample agent documented |
| M9a | App index, `find_app`, `list_app_scans`, fixed `search_scan` | M | M1 | `v1.0.0` | ✅ done, verified against real MobSF |
| M9b | `compare_app_versions` | S | M9a | `v1.0.0` | ✅ done |
| M9c | Retention / `prune_scans` | M | M9a | `v1.0.0` | ✅ done, verified live (dry run, both refusals, real delete, reports kept) |
| M9d | AVD dynamic-analysis run (Selendroid) | M | M6 | `v1.0.0` | ✅ done — verified on an API 30 AVD; the mapper was corrected against the real report |

---

## M0: Foundations & validation
**Goal:** check the assumptions before writing code, and set up the repo.
- Check the market again on GitHub, the official MCP Registry and the Docker MCP Catalog, looking for existing MobSF MCP servers. Decide whether to build or contribute.
- Confirm the latest GA versions of Spring AI and Spring Boot (start.spring.io), and that `@McpTool` and Streamable HTTP are supported.
- Start the official MobSF image by hand and confirm:
  - `MOBSF_API_KEY` pins the REST key
  - the REST endpoints and the shape of the report JSON
  - which Android versions dynamic analysis supports
- Scaffold the repo:
  - Maven project, Java 21, package `io.<you>.mobsfmcp`
  - `LICENSE` (MIT), `NOTICE.md`, `.gitignore`, `README.md` stub
  - GitHub Actions: build, test and a license-header check

**Exit criteria:** the repo builds in CI, the version and API findings are written down in `docs/decisions.md`, and the image namespace and group ID are chosen.

## M1: Core server + dual transport
**Goal:** one jar that serves MCP over stdio **or** Streamable HTTP.
- `MobsfMcpApplication` selects the transport from the `--transport=stdio|http` argument, then the `MCP_TRANSPORT` env var, then the default `http`. Any other value exits with code 2.
- A single `application.yml`. Logs go to a file or stderr, so stdout stays clean in stdio mode.
- `MobsfProperties` (`@ConfigurationProperties`) and a `MobsfClient` built on RestClient, with API-key auth, error mapping and retries.
- The first tools: `mobsf_health` and `list_scans`, to prove the whole path works.

**Exit criteria:** MCP Inspector connects over **both** transports and `list_scans` returns data from a real MobSF. Unit tests with WireMock pass.

## M2: Static analysis tools
**Goal:** a full static scan workflow driven from an agent.
- `FileInputResolver` accepts a mounted path (restricted to `UPLOAD_DIR`), an HTTPS URL (with a size cap and server-side request forgery protection) or base64 (≤10 MB).
- Tools: `upload_app`, `scan_app` (polls with progress notifications), `get_scorecard`, `get_report(section?)`, `download_pdf`, `search_scan`, `compare_scans`, `delete_scan` (`destructiveHint`).
- MCP prompt: `full-apk-security-review`.

**Exit criteria:** DIVA.apk goes through upload → scan → scorecard → report from Claude Code. Every tool is unit tested, including the path-traversal and URL-fetch protections.

## M3: Docker image + compose test harness
**Goal:** anyone can run it with Docker and test it against the official MobSF.
- A multi-stage `Dockerfile` (Temurin 21 JRE) that:
  - runs as non-root
  - has an `ENTRYPOINT` and no `CMD`, so `--transport` passes straight through
  - has a healthcheck in HTTP mode
- `docker-compose.yml` runs `opensecurity/mobile-security-framework-mobsf` and `mobsf-mcp`:
  - `service_healthy` gating, so the MCP server starts only when MobSF is ready
  - a shared API key from `.env`
  - `./apks` and `./reports` volumes
- `.env.example`, plus `scripts/smoke-test.sh` and `scripts/smoke-test.ps1`.
- Testcontainers integration test covering both transports. A CI job runs `docker compose up --wait` → smoke test → `down`.
- Push a preview image to GHCR.

**Exit criteria:**
- `docker compose up` then the smoke test passes on Windows and Linux.
- `docker run … --transport=stdio` works from a Claude Desktop config.
- CI runs the integration job on every PR.

## M4: JSON reports (normalized v1)
**Goal:** static reports in JSON that any agent framework can use.
- `report/model` records: `ScanReport`, `Finding`, `AppInfo`, `Summary`, …
- `schemas/mobsf-mcp-report.v1.json` is generated at build time.
- `StaticReportMapper`, and `ReportStore`, which writes `/data/reports/<hash>/static.json` and `static.raw.json` atomically.
- The `export_report(hash, type, format)` tool. Tools declare an `outputSchema` and return `structuredContent`.
- MCP resources: `mobsf://report/{hash}/static`.

**Exit criteria:** reports for DIVA and InsecureBankv2 validate against the schema in CI (`ajv`). A small Python snippet can read the report without knowing anything about MobSF.

## M5: HTTP hardening + remote uploads
**Goal:** HTTP mode is safe to run on a shared host.
- Spring Security bearer token (`MCP_AUTH_TOKEN` is required in HTTP mode, otherwise startup fails) and Origin-header validation.
- `POST /uploads` returns an `uploadId`, which `upload_app` accepts. `GET /reports/{hash}/{type}.json`.
- API keys and tokens are masked in logs. Upload size limits.

**Exit criteria:**
- Requests without a token get a 401.
- A remote client uploads an APK over HTTP and scans it.
- Security tests are in the suite.

## M6: Dynamic analysis, device & Frida
**Goal:** run dynamic scans through MobSF on a connected device.
- Tools: `device_status` (tells the user how to fix the setup when no device is attached), `mobsfy_device`, `start_dynamic`, `stop_dynamic`, `run_tls_tests`, `get_logcat`, `list_activities`, `start_activity`, `take_screenshot`, `frida_instrument`, `frida_api_monitor`, `list_frida_scripts`, and `adb_command` (only with `ALLOW_ADB_SHELL`, limited to an allowlist).
- `DynamicReportMapper` produces `dynamic.json`. `combined.json` merges static and dynamic findings, deduplicated by id.
- Device setup docs for Genymotion or AVD on Windows or macOS through `host.docker.internal:5555` and for a rooted phone through `adb tcpip`. Also an `emulator` compose profile for Linux with KVM.

**Exit criteria:**
- The full dynamic run completes on a Windows host with Genymotion or AVD: `device_status` → `mobsfy_device` → `start_dynamic` → `frida_instrument` → `export_report(dynamic)`.
- `combined.json` validates against the schema.

## M7: Public release v1.0
**Goal:** a published image that anyone can use.
- A buildx multi-arch image (amd64 and arm64), signed with cosign and with an SBOM, pushed to **Docker Hub** and **GHCR**. It carries the OCI label `licenses=MIT`.
- Listings:
  - the official MCP Registry (`server.json`)
  - a PR to the Docker MCP Catalog (`docker/mcp-registry`)
  - Smithery (optional)
- README: quick start (stdio, HTTP, compose), client configs (Claude, Cursor, VS Code, LangGraph, CrewAI), the report schema, an authorized-testing-only disclaimer, and troubleshooting.
- A CHANGELOG and semantic-version release automation.

**Exit criteria:**
- On a clean machine, `docker pull` and the README quick start work in under 10 minutes.
- The signature verifies.
- The registry listings are live.

## M8: Extras (optional)
- A GraalVM native image, so stdio starts in under a second.
- A sample Spring AI `ChatClient` agent built on the MCP client starter.
- A GitHub Action (`mobsf-mcp-scan`) that scans an APK in CI and fails the build when a severity threshold is exceeded.
- Optional SARIF export generated from the normalized report.

## M9: Road to 1.0.0
The 1.0.0-beta.1 artefacts are built but deliberately unpublished. M9 is what stands between
the beta and a 1.0.0 release; three of its four parts are done.

### M9a: app index, `find_app`, `list_app_scans` — done
MobSF has no API that lists an app's scans: its search matches the first row only and returns
that scan's whole report. `ScanIndex` builds an app-centric view by paging `/api/v1/scans`,
keyed by MD5 so a scan created mid-walk cannot cause a duplicate plus a missed row, cached
briefly and invalidated whenever this server uploads, scans or deletes. `search_scan` now
returns a compact match list instead of a full report or a 404. Missing values from MobSF
(empty strings as much as JSON nulls) collapse to `null`, so blank fields never become a
grouping key and merge unrelated apps.

### M9b: `compare_app_versions` — done
Compares two releases of one app, defaulting to the two most recent scans and reporting which
hashes it chose. Refuses comparisons that would mislead: the same scan twice, an unknown
version, a single-scan app, and mixed scan types.

### M9c: retention / `prune_scans` — done
Manual, dry run by default. A real run must state a rule *and* pass back the hashes the dry
run listed; the newest scan of every app identity is never selectable; unparseable timestamps
never expire; exported reports are kept. Verified live end to end.

### M9d: the AVD run — done
Verified end to end: Selendroid on an API 30 AOSP emulator, driven by MobSF in Docker.
`mobsfy_device` → `start_dynamic` → `test_activities` → `frida_instrument` →
`run_tls_tests` → `stop_dynamic` → `export_report` for both `dynamic` and `combined`, with
both validating against the published schema. The real report is kept as a test fixture at
`src/test/resources/mobsf/dynamic-scan-report.json`, and `DynamicReportMapper` was corrected
against it — `tls_tests` is a flat object of booleans, screenshots live in `activity_tester`,
and `urls` are plain strings.

The emulator recipe that works is **`avbctl disable-verification`**, not `adb disable-verity`,
which leaves an API 30 image unbootable. See `docs/decisions.md`.

### M9d: the original plan — superseded
Every dynamic endpoint and parameter was read from a running MobSF 4.5.4, but no device has
ever been attached, so `DynamicReportMapper` has not seen real dynamic output. Expect to adjust
it and to capture a real `dynamic/report_json` as a test fixture.

Needs an emulator MobSF can drive: **API 30 or lower** (MobSF 4.5.4 reports
`android_supported: 30`), a **non-Play, rootable** image, started with `-writable-system`. The
machine used so far had only an API 36 Play Store AVD, which cannot work. Runbook:
`docs/dynamic-analysis-setup.md`; the Selendroid test APK is already in `./apks`.

Then: bump `pom.xml` to `1.0.0`, move the CHANGELOG entry, and follow `docs/RELEASING.md`.
