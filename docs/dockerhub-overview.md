# Docker Hub overview

Paste everything below the line into the Docker Hub repository description
(**Repository → Manage repository → Description**). The short description, limited to 100
characters, is:

```text
MCP server for MobSF: scan Android and iOS apps, get findings as JSON
```

The text below already points at `sure4docker/mobsf-mcp`.

For the repository image, upload `assets/logo-512.png` (Docker Hub does not accept SVG).

---

# mobsf-mcp

An MCP ([Model Context Protocol](https://modelcontextprotocol.io)) server for
[MobSF](https://github.com/MobSF/Mobile-Security-Framework-MobSF), the Mobile Security
Framework. It lets any agent framework — Claude, Cursor, VS Code, LangGraph, CrewAI, or your
own client — scan mobile apps and read the results.

**Not affiliated with the MobSF project.** This is an independent wrapper that talks to MobSF
over its REST API. MobSF runs as its own container under its own licence (GPL-3.0).
Only analyze apps you are authorized to test.

## What it does
- **Upload** an app from a mounted folder, an HTTPS URL, inline base64, or an HTTP upload
- **Static analysis:** security score, findings, permissions, manifest, network config,
  secrets, trackers, certificate, code — sliced by section so a report never floods an agent
- **Dynamic analysis** on a connected Android device: activity testing, TLS tests, logcat and
  Frida instrumentation
- **Reports** as normalized JSON in a versioned schema any tool can parse, as MobSF's own
  JSON, or as **SARIF 2.1.0** for GitHub code scanning

One image serves both transports: **stdio** for desktop clients that launch the server
themselves, and **Streamable HTTP** for a server several clients share.

## Quick start

Everything at once, with MobSF included:

```bash
curl -O https://raw.githubusercontent.com/sure4docker/mobsf-mcp/main/docker-compose.yml
curl -o .env https://raw.githubusercontent.com/sure4docker/mobsf-mcp/main/.env.example
# edit .env: set MOBSF_API_KEY and MCP_AUTH_TOKEN
docker compose up -d
```
The MCP endpoint is `http://localhost:8080/mcp` and the MobSF UI is `http://localhost:8000`.

Against a MobSF you already run:

```bash
docker run -d -p 127.0.0.1:8080:8080 \
  -e MOBSF_URL=http://host.docker.internal:8000 \
  -e MOBSF_API_KEY=<your MobSF API key> \
  -e MCP_AUTH_TOKEN=<a token you choose> \
  -v "$PWD/apks:/data/apks" -v "$PWD/reports:/data/reports" \
  sure4docker/mobsf-mcp:latest
```

As a stdio server launched by your MCP client:

```json
{ "mcpServers": { "mobsf": {
  "command": "docker",
  "args": ["run","-i","--rm",
           "-e","MOBSF_URL=http://host.docker.internal:8000",
           "-e","MOBSF_API_KEY=<key>",
           "-v","C:/apks:/data/apks",
           "sure4docker/mobsf-mcp:latest","--transport=stdio"] } } }
```

## Configuration
| Variable | Default | Purpose |
|---|---|---|
| `MCP_TRANSPORT` | `http` | `stdio` or `http`; the `--transport=` argument overrides it |
| `MOBSF_URL` | `http://localhost:8000` | Base URL of MobSF |
| `MOBSF_API_KEY` | none | MobSF REST API key, from the MobSF web UI |
| `MCP_AUTH_TOKEN` | none | Required in http mode; clients send `Authorization: Bearer <token>` |
| `UPLOAD_DIR` / `REPORT_DIR` | `/data/apks` / `/data/reports` | Mounted input and output folders |

## Notes
- Runs as a non-root user. HTTP mode refuses to start without `MCP_AUTH_TOKEN`, and publishing
  the port on loopback is recommended.
- Images are built for `linux/amd64` and `linux/arm64` and signed with cosign.
- MIT licensed. Source, full tool list and documentation:
  https://github.com/sure4docker/mobsf-mcp
