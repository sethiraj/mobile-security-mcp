# Security policy

## Reporting a vulnerability
Please report security issues privately through GitHub's **Report a vulnerability** button
(Security → Advisories) rather than a public issue. Include what you did, what happened and
the version or image digest.

## What this tool is
mobsf-mcp lets an AI agent drive MobSF, which analyses mobile apps. Keep three things in
mind when deploying it.

**Only analyze apps you are authorized to test.** Scanning someone else's app may breach
their terms of service or local law. Dynamic analysis installs and runs the app on a device
you control.

**Treat the MCP endpoint as privileged.** Anyone who can reach it can upload files, start
scans, read reports and — with dynamic analysis — run apps and Frida scripts on the attached
device. Therefore:
- HTTP mode refuses to start without `MCP_AUTH_TOKEN`. Do not set `MCP_ALLOW_ANONYMOUS=true`
  on a shared machine.
- Publish the port on loopback (`-p 127.0.0.1:8080:8080`) unless something else authenticates
  in front of it.
- `adb_command` is off unless `ALLOW_ADB_SHELL=true`, and even then only read-only commands
  are accepted. Enabling it gives whoever can call the server device-level inspection.

**Scan results are sensitive.** Reports can contain hardcoded secrets, endpoints and file
paths found in the app. `./reports` and the `/reports/...` endpoint hold that material.

## What the server does to protect you
- The MobSF API key and the bearer token are masked in logs, errors and tool results.
- Uploads are limited by size and extension, and paths cannot escape the upload directory.
- URL downloads are HTTPS-only and refuse private, loopback and link-local addresses.
- Requests carrying a browser `Origin` are refused unless listed in `MCP_ALLOWED_ORIGINS`,
  which blocks DNS-rebinding against a locally bound server.
- The container runs as a non-root user, and images are signed with cosign.

## Third-party components
MobSF runs as its own container under its own license (GPL-3.0) and does its own analysis;
report MobSF issues to the MobSF project. See `NOTICE.md`.
