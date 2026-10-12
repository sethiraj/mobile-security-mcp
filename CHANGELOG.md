# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `find_app` and `list_app_scans`: an app-centric view of MobSF's scans, built by paging `/api/v1/scans`, with every scanned version of an app and an explicit ambiguity flag when two apps share a name.
- `prune_scans`: reclaims MobSF disk by deleting old scans, with a dry run by default, an explicit rule and hash list required for a real run, the newest scan of every app protected, and exported reports left in place.
- `compare_app_versions`: compares two releases of one app (defaulting to the two most recent scans), reporting the score delta and which high-severity findings differ.

### Changed
- `search_scan` now returns a compact list of matches instead of MobSF's first match as a full report, and reports no matches as `matchCount: 0` rather than a 404.
- Missing values from MobSF (empty strings, JSON nulls and absent fields alike) are normalized to `null`, so agents can tell "not provided" from a real value and apps with no package name are never grouped together.
- `scan_app` fails with instructions when MobSF runs in async mode (`MOBSF_ASYNC_ANALYSIS=1`), which this server does not support; compose pins it to `0`.

## [1.0.0-beta.2] - 2026-10-12

### Added
- `suppress_finding`, `unsuppress_finding` and `list_suppressions`: dismiss a reviewed finding so it stops failing scans and CI gates. MobSF suppresses per package name, so a suppression carries to later scans of the same app, and the tools say so.
- `view_source`: read the file behind a finding (from its `evidence.file`), so an agent can propose a fix instead of only reporting one. Long files are truncated rather than flooding the caller.

### Fixed
- `suppress_finding` rejects a rule id that no finding in the scan uses, listing the real ones. MobSF accepts any id and reports success, so a typo would silently suppress nothing and the finding would reappear in every later report.

## [1.0.0-beta.1] - 2026-09-26

First beta, not published. Static analysis, JSON reports and the hardened HTTP mode have been
exercised end to end against the official MobSF image. The dynamic-analysis tools are built
against MobSF 4.5.4's API but have not yet been run against a real device or emulator, and
more features are planned, so this is a beta rather than 1.0.0.

### Added
- **MCP server on Spring AI 2.0** serving **stdio** or **Streamable HTTP** from one jar and
  image, selected with `--transport=stdio|http` or `MCP_TRANSPORT` (default `http`).
- **Static analysis tools:** `mobsf_health`, `upload_app`, `scan_app`, `scan_status`,
  `get_scorecard`, `get_report`, `download_pdf`, `list_scans`, `search_scan`,
  `compare_scans`, `delete_scan`.
- **App input in four ways:** a path in the mounted upload directory, an HTTPS URL, inline
  base64, or an `uploadId` from `POST /uploads`.
- **Normalized JSON reports** in the versioned `mobsf-mcp-report/v1` schema
  (`schemas/mobsf-mcp-report.v1.json`), via `export_report` and `get_normalized_report`,
  plus the `mobsf://report/{hash}/static` and `.../raw` resources.
- **Dynamic-analysis tools:** `device_status`, `mobsfy_device`, `start_dynamic`,
  `stop_dynamic`, `get_dynamic_report`, `test_activities`, `start_activity`,
  `run_tls_tests`, `get_logcat`, `frida_instrument`, `frida_api_monitor`, `frida_logs`,
  `list_frida_scripts`, and `adb_command` (off unless `ALLOW_ADB_SHELL=true`, read-only).
- **`full-apk-security-review` prompt** covering the whole workflow.
- **docker compose stack** with the official MobSF image, health-gated startup and a shared
  API key, plus a smoke test that scans an app and validates the report against the schema.
- **Multi-architecture image** (`linux/amd64`, `linux/arm64`) that runs as a non-root user.

### Security
- HTTP mode refuses to start without `MCP_AUTH_TOKEN` (`MCP_ALLOW_ANONYMOUS=true` opts out).
- Requests carrying an `Origin` header are refused unless listed in `MCP_ALLOWED_ORIGINS`,
  which blocks DNS-rebinding from a browser.
- Uploads are limited by size and extension; paths cannot escape the upload directory; URL
  downloads are HTTPS-only and refuse private or loopback addresses.
- The MobSF API key is masked in every log line, error and tool result.

### Planned before 1.0.0
- A verified dynamic-analysis run against an Android emulator (AVD), which may change the
  dynamic report mapping.
- Further features, to be decided.

[Unreleased]: https://github.com/sure4docker/mobsf-mcp/compare/v1.0.0-beta.2...HEAD
[1.0.0-beta.2]: https://github.com/sure4docker/mobsf-mcp/releases/tag/v1.0.0-beta.2
[1.0.0-beta.1]: https://github.com/sure4docker/mobsf-mcp/releases/tag/v1.0.0-beta.1
