# Third-party notices

mobsf-mcp is licensed under the MIT License, copyright (c) 2026 Surendran E (see `LICENSE`).
The security analysis itself is MobSF's work; this project is an independent MCP wrapper and is
not affiliated with or endorsed by the MobSF project.

## MobSF (Mobile Security Framework)
- Project: https://github.com/MobSF/Mobile-Security-Framework-MobSF
- Authors: Ajin Abraham and the MobSF contributors
- License: GNU General Public License v3.0 (GPL-3.0), https://www.gnu.org/licenses/gpl-3.0.html

mobsf-mcp does **not** include, bundle or link MobSF code. It talks to a separately running MobSF instance only through MobSF's REST API over the network. The docker-compose setup pulls the official `opensecurity/mobile-security-framework-mobsf` image as its own container. MobSF stays under its own license.

## Bundled dependencies (Apache-2.0)
The runnable jar and Docker image include these libraries, all licensed under Apache-2.0:
- Spring Boot, Spring Framework, Spring AI
- MCP Java SDK (`io.modelcontextprotocol.sdk`)
- Jackson, Project Reactor, Apache Tomcat (embedded)

Run `./mvnw dependency:list` for the full list of dependencies.
