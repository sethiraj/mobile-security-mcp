# Decisions and validation log

## M0: validation (2026-09-22)

### Versions (checked against Maven Central)
| Component | Version | Notes |
|---|---|---|
| Spring AI | **2.0.1** (latest GA) | Its MCP starters depend on Spring Boot 4.1.1 |
| Spring Boot | **4.1.1** | 4.2.0 is still at milestone releases |
| MCP Java SDK | 2.0.0 | Comes in through the Spring AI BOM |
| Jackson | 3.x (`tools.jackson`) | Boot 4 default. The Jackson 2 annotations are still on the classpath |
| Java | 21 (Temurin) | |

### Spring AI MCP server facts (read from the 2.0.1 jars)
- `spring-ai-starter-mcp-server-webmvc` provides both transports:
  - `spring.ai.mcp.server.protocol` accepts `SSE | STREAMABLE | STATELESS`, and the default is `streamable`.
  - `spring.ai.mcp.server.stdio=true` together with `WebApplicationType.NONE` swaps in `StdioServerTransportProvider`.
- Annotations live in `org.springframework.ai.mcp.annotation`: `@McpTool` (with `annotations = @McpAnnotations(readOnlyHint, destructiveHint, …)` and `generateOutputSchema`), `@McpToolParam`, `@McpResource`, `@McpPrompt` and `@McpProgress`. The annotation scanner is on by default.
- Boot 4 split `RestClient.Builder` into its own module, so the project needs `spring-boot-starter-restclient`.

### MobSF API facts (M3: checked against the live official image, MobSF v4.5.4)
- [x] The official image honors `MOBSF_API_KEY`; `init.py:api_key()` prefers `MOBSF_API_KEY_FILE`, then `MOBSF_API_KEY`, then the generated `secret` file.
- [x] `X-Mobsf-Api-Key` is accepted (`api_middleware.py` also accepts a plain `Authorization` header).
- [x] `GET /api/v1/scans?page=&page_size=` returns `{content:[…], count, num_pages}` as assumed.
- [x] The report JSON contains every section key the `get_report` tool slices: `manifest_analysis`, `network_security`, `permissions`, `secrets`, `trackers`, `certificate_analysis`, `code_analysis`, `binary_analysis`, plus `sbom` and `appsec`.
- [x] **The scorecard has no `grade` field**, and `total_trackers` is the size of MobSF's tracker database (432), not the number found. The count found is `trackers`. The tool now reports `trackersDetected` / `trackersChecked` and no grade.
- [x] Dynamic analysis supports up to **API 30 (Android 11)** in MobSF 4.5.4: `GET /api/v1/dynamic/get_apps` returns `android_supported: 30`, and `device_status` warns when the attached device is newer.

### Problems found by running the real stack (M3)
| Problem | Cause | Fix |
|---|---|---|
| MobSF exited at startup with `TypeError: expected str … not NoneType` | Its home directory does not exist in the image, so the named volume was created root-owned and MobSF (uid 9901) could not write to it | A `mobsf-init` busybox service chowns the volume to 9901 before MobSF starts |
| Upload rejected: `{"file": "This field is required."}` | Spring streams multipart bodies with chunked encoding, and Django discards chunked multipart uploads | `MobsfClient.upload` builds the multipart body by hand with `BodyPublishers.concat`, so the JDK client sends an exact `Content-Length` |
| Multipart upload failed with `RST_STREAM` against a local server | The JDK HTTP client negotiating HTTP/2 | All MobSF calls are pinned to HTTP/1.1 |
| Container would not start: `No default constructor` for `FileInputResolver` | Two constructors (one for tests), so Spring could not choose | `@Autowired` on the real one |
| The smoke script hung forever | Streamable HTTP answers on an SSE stream the server keeps open | The script stops reading at the first `data:` line |

### Market re-check: **pending (manual)**
GitHub and web search couldn't be reached from the build machine. Before M7 (public release), search GitHub, registry.modelcontextprotocol.io, the Docker MCP Catalog and Smithery for `mobsf mcp`. Then decide whether to publish separately or contribute upstream.

## M4/M5 findings (normalized reports and HTTP hardening)
- The mapper is tested against a report captured from a real MobSF 4.5.4 scan, kept at `src/test/resources/mobsf/source-scan-report.json`, so it is not written against guesses.
- MobSF's severities are kept as they are (`high`, `warning`, `info`, `secure`, `hotspot`) instead of being remapped, so findings stay comparable with MobSF's own UI. MobSF rates some rules more mildly than expected: insecure `Random` is `warning`, not `high`.
- Null fields are left out of the JSON (`@JsonInclude(NON_NULL)` on every report record) so a static-only report has no empty `dynamicAnalysis`, and it validates cleanly.
- The schema is generated from the records by a test that fails when the committed file drifts. Regenerate with `mvn test -Dtest=ReportSchemaTest -Dmobsf.schema.write=true`.
  - The OpenAPI-only format values (`"format": "double"`) had to stay off, because strict validators such as ajv reject them.
  - The Jackson module option `FLATTENED_ENUMS_FROM_JSONVALUE` is needed so the schema lists `scanType` as the lowercase value the JSON actually carries.
- MCP resource methods must return `String` (or an MCP content type); returning a `record` or `JsonNode` fails at startup, so both resources serialize to JSON text themselves.
- HTTP mode now refuses to start without `MCP_AUTH_TOKEN` (`MCP_ALLOW_ANONYMOUS=true` opts out). A request that carries an `Origin` header is rejected unless it is in `MCP_ALLOWED_ORIGINS`, which blocks DNS-rebinding from a browser while leaving ordinary MCP clients (which send no Origin) working.
- `POST /uploads` keeps the client's file name in a sidecar file, because MobSF records whatever name it is given and the stored file is named after a generated id.

## Design decisions
| Decision | Why |
|---|---|
| Transport comes from `--transport=stdio\|http` (or `--transport stdio`), then `MCP_TRANSPORT`, then the default `http`. An invalid value exits with code 2 | One image covers both uses, and the choice has to be made before Spring decides whether to start a web server |
| All logging goes to **stderr** (`logback-spring.xml`), with no log file | In stdio mode stdout must carry only JSON-RPC. `docker logs` still shows stderr |
| `MOBSF_URL` and `MOBSF_API_KEY` are **not** required at startup | Registries and inspectors can list the tools without secrets. `mobsf_health` tells the user what to fix |
| The API key is masked everywhere (`****1234`) | Agents and logs never see the secret |
| In M1 the HTTP bearer token (`MCP_AUTH_TOKEN`) is optional. It becomes mandatory in M5 | Local development stays easy until the hardening milestone |
| groupId and package are `io.mobsfmcp` for now | A placeholder until the public namespace (Docker Hub, GitHub org) is chosen |
| Copyright holder is "mobsf-mcp contributors" | To be replaced with a person or organization name before the first public release |
| `scan_app` waits for the scan but returns `status: "running"` on timeout instead of failing | MobSF's `/api/v1/scan` blocks for the whole scan (minutes). The agent can poll `scan_status` and still get results |
| `compare_scans` diffs two scorecards locally instead of calling a MobSF compare endpoint | Fewer assumptions about MobSF's API, and it returns exactly what an agent needs |
| MCP progress notifications during scans are deferred | `scan_app` already reports progress through `scan_status`; notifications need exchange plumbing and most clients ignore them. Revisit in M6 |

## M6 findings (dynamic analysis)
Every endpoint and parameter name below was read from the running MobSF 4.5.4 container, not guessed. Dynamic analysis itself could **not** be exercised end to end here, because it needs an attached Android device; see `docs/dynamic-analysis-setup.md`.

- `GET /api/v1/dynamic/get_apps` is the only dynamic endpoint that is **GET**; everything else is POST. Sending POST returns HTTP 405.
- It answers **200 even when no device is attached**, with `identifier: null`. So "is a device connected?" is decided by that field, not by the status code.
- The same response carries `android_sdk` and `android_supported`, so `device_status` can warn when a device is newer than MobSF supports.
- Required form fields, from MobSF's API views: `start_analysis` and `stop_analysis` take `hash`; `mobsfy` takes `identifier`; `logcat` takes `package`; `activity` takes `hash` and `test` (`activity` or `exported`); `start_activity` takes `hash` and `activity`; `instrument` takes `hash`, `default_hooks`, `auxiliary_hooks` and `frida_code`; `list_scripts` and `get_script` take `device`; `adb_command` takes `cmd`.
- **There is no Android screenshot API.** `api_screenshot` exists in the code but is not routed in `urls.py`, so no screenshot tool is offered; screenshots still arrive through `test_activities` and the dynamic report.
- The dynamic report's keys were read from MobSF's `dynamic_report` view: `tls_tests`, `apimon`, `droidmon`, `urls`, `domains`, `clipboard`, `sqlite`, `xml`, `others`, `screenshots`, `activity_tester`, `exported_activity_tester`, `trackers`, `runtime_dependencies`, `package`.
- `adb_command` stays off unless `ALLOW_ADB_SHELL=true`, and even then only read-only commands are accepted (no chaining, no shell metacharacters). Arbitrary adb would turn the MCP server into remote shell access to the device.

## M7/M8 findings (release preparation and extras)
- The Dockerfile now pins the build stage to `--platform=$BUILDPLATFORM`. Without it, buildx runs the whole Maven build under QEMU for each extra architecture; since the jar is architecture-independent, only the runtime layer needs to vary. A real `linux/amd64,linux/arm64` build was verified locally.
- **Version 0.9.0, not 1.0.0.** Static analysis, reports and HTTP hardening are verified against real MobSF, but the dynamic tools have never run against a device. 1.0.0 should wait for that.
- The SARIF export was validated against the official SARIF 2.1.0 schema from schemastore. Two details matter for GitHub code scanning: every result needs a location (findings with no file are anchored to the app itself), and `properties.security-severity` on the rule is what GitHub shows as the alert severity.
- `scripts/ci-scan.sh` was run against the live stack: it exits 1 when the high-severity or minimum-score gate trips and 0 otherwise, and writes both the normalized and SARIF reports.
- Placeholders are all the single token `OWNER`, rewritten by `scripts/set-namespace.sh` (tested on a copy of the tree), so choosing a namespace is one command rather than a hunt through files.
- **Not done here, and why:** pushing to Docker Hub (not logged in, and publishing is the owner's decision), creating the GitHub repository and the registry listings (GitHub is unreachable from this machine), and building the GraalVM native image (no GraalVM toolchain; Spring AI's reflection would likely need hints first).

## M9a findings (app index, and how missing values are handled)
- **MobSF cannot list an app's scans.** `POST /api/v1/search` → `find_checksum()` matches `FILE_NAME`, then `PACKAGE_NAME`, then `APP_NAME` with `icontains` and returns **the first row only**, after which `api_search` returns that scan's entire report; no match is a 404 whose message ("You can search by MD5, app name…") reads like a malformed query. `ScanIndex` therefore builds an app-centric view by paging `/api/v1/scans`, and `search_scan` now returns a compact match list instead of a full report or a 404.
- **Paging has to be keyed by hash.** The list is newest-first, so a scan created mid-walk shifts every row down a page: a naive walk would return one row twice and miss another entirely. The walk accumulates into a `LinkedHashMap` keyed by MD5 and stops on a short page, a page that adds nothing, or `num_pages`.
- **TTL caching alone would break the main workflow** — `scan_app` followed by `find_app` would not show the new scan — so the index is invalidated explicitly on upload, scan and delete, and every tool takes `refresh`.
- **Absent data is an empty string, not null.** MobSF's Django models default to `''`, so `/api/v1/scans` returns `APP_NAME: ""`, `VERSION_NAME: ""` and, for source-zip scans, `PACKAGE_NAME: ""`. JSON nulls also occur (`identifier` on `dynamic/get_apps`). `io.mobsfmcp.util.Json` collapses missing, null and blank to `null`, which matters twice: agents can tell "not provided" from a real value, and the grouping key (`packageName`, else `appName`, else `fileName`) never keys on `""` and merges unrelated apps. Exported reports still omit nulls; tool results keep them explicit.
- **Duplicate names are real.** The live instance has two scans named `vulndemo-src.zip` with different hashes — one with a package name, one without — and `find_app` correctly reports them as two groups with `ambiguous: true`.
- **Verified with a real APK at last.** `selendroid-test-app-0.17.0.apk` comes from Maven Central, which is reachable here while GitHub and F-Droid are not. It scans in ~13s, scores 27/100 with 5 high findings, and is the first test app with a genuine package name (`io.selendroid.testapp`) and version (`0.17.0`).
- **Synchronous MobSF is a requirement, not an assumption.** `MOBSF_ASYNC_ANALYSIS=1` makes `/api/v1/scan` return `{task_id, message}` instead of a report; `scan_app` detects that shape and fails with instructions rather than calling `get_scorecard` on a report that does not exist. Compose pins `MOBSF_ASYNC_ANALYSIS: "0"`. Only static analysis is affected — the setting is referenced solely by `StaticAnalyzer`.
- Stale incremental compilation hid a broken test signature during this work; `rm -rf target/test-classes` before trusting a green `test-compile` after a constructor change.

## M9b/M9c findings (version comparison and retention)
- **`compare_app_versions` defaults to the two most recent scans** and reports which hashes it chose, so an agent can see what was actually compared. It refuses the comparisons that would mislead: the same scan twice, an unknown version (listing the known ones), an app with a single scan, and mixed scan types — diffing an APK against a source zip produces a meaningless delta.
- **The "protect the newest scan of each app identity" rule had a bug worth recording.** The identity key originally included the file name, but release artefacts are named per version (`demo-1.0.apk`, `demo-2.0.apk`), so every scan became its own identity and *nothing* was ever selectable for pruning. The identity is now package name plus app name; the file name is used only as a grouping fallback. Caught by a test that expected two of four scans to be prunable and got zero.
- **Retention is deliberately hard to fire by accident:** a dry run is the default, a real run must both state a rule (`keepPerApp` and/or `maxAgeDays`) *and* pass the hashes the dry run listed, the newest scan of every app identity is never selectable, and the real run re-plans against a fresh index so anything no longer selected is skipped rather than deleted.
- **Unparseable timestamps never expire.** `maxAgeDays` parses ISO-8601 and MobSF's `yyyy-MM-dd HH:mm:ss`; anything else is treated as "not old enough", so a format change in MobSF cannot cause a deletion.
- **Exported reports are never touched.** Pruning frees MobSF's copy (the app plus its decompiled sources, which is what grows); `<reportDir>/<hash>/*.json` and `<reportDir>/<hash>.pdf` stay as the audit trail, and the tool result says so explicitly.
- Verified live: a dry run with `keepPerApp=1` selected exactly one scan with its reason; a real run without `hashes`, and one without a rule, were both refused; the real run deleted only the listed scan; `find_app` then showed one version instead of two, and the deleted scan's JSON, SARIF and PDF were all still on disk.

## beta.2 findings (suppression and source viewing)
- **MobSF accepts any rule id for a suppression and reports success.** Suppressing `android_debuggable` (the real id is `app_is_debuggable`) stored the entry, changed nothing, and looked like it had worked — the finding simply stayed in every later report. `suppress_finding` now checks the id against the scan's own findings and, when it does not match, lists the real ones. Verified live: the bad id is refused with the four real manifest rule ids; the correct one drops Selendroid from 5 high findings / 27 to 4 / 31, and un-suppressing restores it.
- **`list_suppressions` is shaped per (package, type), not per suppression:** `{"message":[{"PACKAGE_NAME":…,"SUPPRESS_RULE_ID":[…],"SUPPRESS_FILES":{rule:[files]},"SUPPRESS_TYPE":"manifest"}]}`. The first implementation assumed one row per rule and returned nulls; only the live run showed it.
- **Suppression is per package name, not per scan**, so it applies to future scans of the same app. That is the useful behaviour for a release pipeline, but it means a suppression outlives the scan it was created from — the tool descriptions state this.
- MobSF only suppresses `code` and `manifest` findings; there is no suppression for network, certificate or tracker findings.
- Selendroid's APK scan has **no code findings at all** (`code_analysis.findings` is empty), so `view_source` was verified against `AndroidManifest.xml` with `type=xml` instead — 33 lines of real manifest.

## M9d findings (the AVD run that finally worked)
Dynamic analysis has now been verified end to end against a real emulator: Selendroid on an API 30 AOSP AVD, driven by MobSF 4.5.4 in Docker.

- **`adb disable-verity` makes an API 30 image unbootable on emulator 36.3.10.** Reproduced three times: verity is disabled, the device reboots, and adbd never returns — the emulator's ports listen but refuse connections. **`adb shell avbctl disable-verification` is the one that works**, and after a single reboot `/system` is genuinely writable. This is the difference between a working dynamic setup and hours of dead ends; the MobSF docs recommend the `disable-verity` route.
- **`adb remount` reports "remount succeeded" while `/` stays read-only** when verity is still on (`/` is a device-mapper device). The success message cannot be trusted — test with an actual write.
- **Docker cannot reach the emulator without help.** The emulator binds adb to `127.0.0.1`, and `host.docker.internal` resolves to an IPv6 address inside Docker Desktop, so a forwarder must listen dual-stack. `node` listening on `:5556` and forwarding to `127.0.0.1:5555` is enough; `MOBSF_ANALYZER_IDENTIFIER=host.docker.internal:5556` then works.
- **The dynamic report's real shapes differ from what MobSF's source suggested**, which is exactly why this run mattered:
  - `tls_tests` is a **flat object of booleans** (`has_cleartext`, `tls_misconfigured`, `no_tls_pin_or_transparency`, `pin_or_transparency_bypassed`), each named for the problem, so `true` means the problem is present and `false` is a pass — now mapped to `high` and `secure` respectively, rather than `high`/`info`.
  - `urls` are plain strings; `domains` is an object keyed by domain carrying reputation and geolocation metadata.
  - **Screenshots are not in `screenshots`** (empty in a real run) but in `activity_tester`, keyed by image file and valued by the activity shown.
  - `apimon` and `droidmon` come back as empty objects when nothing was captured.
- Selendroid's combined report: 12 findings, 8 static and 4 dynamic, 6 high — the dynamic one being real cleartext traffic observed at runtime, which static analysis flagged only as a manifest setting.
- A container from an unrelated project held port 8080, so `docker-compose.yml` now takes `MCP_HOST_PORT` (default 8080) rather than failing to start.
