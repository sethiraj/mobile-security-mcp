# Example: a Spring AI agent that drives mobsf-mcp

A minimal agent that connects to mobsf-mcp as an MCP **client** and lets a model run the
scan workflow itself. It shows the other half of the picture: mobsf-mcp is the server, and
any MCP-capable framework can be the client.

This example is not part of the main build. It needs an API key for a model provider, so it
has not been run here.

## Run it
```bash
docker compose up -d            # in the repository root: MobSF + mobsf-mcp
cd examples/spring-ai-agent
export ANTHROPIC_API_KEY=...    # or OPENAI_API_KEY, with the matching starter
export MCP_AUTH_TOKEN=...       # the same token as in ../../.env
mvn spring-boot:run -Dspring-boot.run.arguments="--app=/data/apks/diva-beta.apk"
```

## pom.xml
```xml
<dependencies>
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
  </dependency>
</dependencies>
```

## application.yml
```yaml
spring.ai:
  mcp.client:
    streamable-http:
      connections:
        mobsf:
          url: http://localhost:8080/mcp
  anthropic.api-key: ${ANTHROPIC_API_KEY}
```
The bearer token is added by a request customizer, because the token is per-connection.

## The agent
```java
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }

    @Bean
    ApplicationRunner runner(ChatClient.Builder chatClient, SyncMcpToolCallbackProvider tools,
            @Value("${app}") String app) {
        return args -> {
            String answer = chatClient.build()
                .prompt()
                .toolCallbacks(tools)          // every mobsf-mcp tool, handed to the model
                .user("""
                        Review the mobile app at %s with the MobSF tools.
                        Call mobsf_health first. Upload and scan the app, read the scorecard,
                        then fetch the manifest, network, secrets and code sections.
                        Summarise the high-severity findings and how to fix each one.
                        Report only what the tools returned.
                        """.formatted(app))
                .call()
                .content();
            System.out.println(answer);
        };
    }
}
```

## Other frameworks
The server is framework-neutral. Anything that speaks MCP can drive it:

- **Claude Code:** `claude mcp add --transport http mobsf http://localhost:8080/mcp --header "Authorization: Bearer $MCP_AUTH_TOKEN"`
- **Python (official MCP SDK):**
  ```python
  from mcp import ClientSession
  from mcp.client.streamable_http import streamablehttp_client

  async with streamablehttp_client(
          "http://localhost:8080/mcp",
          headers={"Authorization": f"Bearer {token}"}) as (read, write, _):
      async with ClientSession(read, write) as session:
          await session.initialize()
          result = await session.call_tool("upload_app", {"path": "/data/apks/app.apk"})
  ```
  The same session works as a LangGraph or CrewAI tool source through their MCP adapters.
- **No MCP at all:** in http mode, `POST /uploads` and `GET /reports/<hash>/static.json` are
  plain HTTP, and `scripts/ci-scan.sh` shows the whole flow with nothing but `curl`.
