# Example: APK security chat

A small Spring AI application with a Thymeleaf chat interface. You type a request; the
model decides which tools to call. It has two sets:

- **mobsf-mcp**, reached as an MCP client, for upload, scan, findings, device status and
  the whole dynamic-analysis workflow.
- **Appium**, as local Spring AI tools, for driving the app on the same emulator MobSF is
  watching — read the screen, tap, type, press back.

That combination is the point. MobSF's own activity tester launches each activity and
screenshots it, which finds an exported screen but never gets past a login. Appium does,
so the app reaches the states where it really uses the network while MobSF's Frida hooks
are recording.

```
browser ──> Spring AI chat ──┬── MCP ────> mobsf-mcp ──> MobSF ──┐
                             │                                   ├──> same emulator
                             └── Appium ─────────────────────────┘
```

## What you need running

| | |
|---|---|
| **MobSF + mobsf-mcp** | `docker compose up -d` in the repository root |
| **An Android emulator** | rooted, writable system — see [`docs/dynamic-analysis-setup.md`](../../docs/dynamic-analysis-setup.md) |
| **An Appium server** | `npm i -g appium && appium driver install uiautomator2 && appium` |
| **Azure AI Foundry** | a deployment, its endpoint and a key |

The app **fails to start** if mobsf-mcp is unreachable. That is deliberate: without it
there are no security tools and the chat has nothing to offer. Start the stack first.

## Configuration

Azure AI Foundry serves the OpenAI API, so the OpenAI starter talks to it with nothing but
a different base URL. The deployment name goes in `model` — for an Anthropic deployment
that is whatever you named it in Foundry.

```powershell
$env:AZURE_AI_BASE_URL  = "https://<your-resource>.services.ai.azure.com/openai/v1"
$env:AZURE_AI_API_KEY   = "<your key>"
$env:AZURE_AI_DEPLOYMENT= "claude-sonnet-4-5"
$env:MCP_AUTH_TOKEN     = "<the same value as MCP_AUTH_TOKEN in ../../.env>"
$env:APPIUM_DEVICE      = "emulator-5554"
```

`AZURE_AI_BASE_URL` must stop at `/openai/v1`; Spring AI appends `/chat/completions`
itself. If your Foundry resource exposes a different path, override
`spring.ai.openai.chat.completions-path`.

Everything else is in [`application.yml`](src/main/resources/application.yml), including
the MCP request timeout, raised to 20 minutes because a dynamic scan blocks for minutes.

## Run it

```powershell
mvn spring-boot:run
```

Then open <http://localhost:8099>.

## Try

> Scan /data/apks/selendroid-test-app-0.17.0.apk and summarise the high findings

Static only: upload, scan, scorecard. Note the path is inside the **MobSF container** —
that is the `./apks` folder in the repository root.

> Is a device connected for dynamic analysis?

Calls `device_status` and tells you what is missing if nothing is attached.

> Run the app on the emulator, tap through it, and tell me what it sends in the clear

The interesting one. The model starts dynamic analysis, instruments with Frida, opens an
Appium session, reads the screen and works through it, then stops and reports.

## How the two halves are wired

`ChatConfig` builds one `ChatClient` from both sources:

```java
builder.defaultSystem(SYSTEM_PROMPT)
    .defaultToolCallbacks(mcpTools.toArray(ToolCallbackProvider[]::new))  // mobsf-mcp
    .defaultTools(appiumTools)                                           // local @Tool methods
    .build();
```

mobsf-mcp requires a bearer token in HTTP mode, and the MCP client properties have no
field for headers, so the token is added by an `McpSyncHttpClientRequestCustomizer` bean.

## Caveats, honestly

- **Not run against a real Azure AI Foundry deployment here.** The wiring is verified —
  the app starts, completes the MCP handshake with mobsf-mcp and serves the UI — but no
  model call has been made from this machine, so treat the Foundry URL shape as the thing
  to check first if a request fails.
- **The Appium tools have not been exercised against a live Appium server.** They compile
  and register; the UiAutomator2 options and the XPath strategies are the ordinary ones,
  but expect to adjust the selectors for your app.
- **One Appium session at a time**, on purpose. Two drivers on one device fight over the UI.
- **Conversation state is per HTTP session and in memory.** Restarting loses it. There is
  no chat memory advisor, so the model sees one message at a time rather than the history —
  add `MessageChatMemoryAdvisor` if you want follow-up questions to carry context.
- This example is not part of the main build and has no tests.
