// SPDX-License-Identifier: MIT
package io.mobsfmcp.example;

import io.mobsfmcp.example.appium.AppiumTools;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.List;

@Configuration
class ChatConfig {

	private static final String SYSTEM_PROMPT = """
			You are a mobile application security assistant. You have two tool sets.

			mobsf-mcp runs MobSF: upload an app, scan it, read findings, and run dynamic
			analysis on a connected Android device. Appium drives that same device, so you
			can exercise the app yourself while MobSF is watching it.

			How to work:
			- For a static review: upload_app, then scan_app, then get_scorecard or get_report.
			- For a runtime review: check device_status first. Then start_dynamic, then
			  frida_instrument, then drive the app with the appium tools, then stop_dynamic
			  and export_report with type=combined.
			- Frida hooks only record while frida_instrument is holding them, so start the
			  Appium interaction and the instrumentation together rather than one after the other.
			- Never invent a finding. If a tool returns nothing, say that it returned nothing.

			Answer briefly. Name the exact tool you called and what it returned. When you
			report findings, give the severity and what an engineer should change.
			""";

	/**
	 * mobsf-mcp requires a bearer token in HTTP mode, and the MCP client properties have
	 * no place for headers, so the token goes on every request through this customizer.
	 */
	@Bean
	McpSyncHttpClientRequestCustomizer mobsfAuthorization(@Value("${mobsf.mcp.auth-token:}") String token) {
		return (request, method, uri, body, context) -> {
			if (StringUtils.hasText(token)) {
				request.header("Authorization", "Bearer " + token);
			}
		};
	}

	@Bean
	ChatClient chatClient(ChatClient.Builder builder, List<ToolCallbackProvider> mcpTools, AppiumTools appiumTools) {
		return builder.defaultSystem(SYSTEM_PROMPT)
			.defaultToolCallbacks(mcpTools.toArray(ToolCallbackProvider[]::new))
			.defaultTools(appiumTools)
			.build();
	}

}
