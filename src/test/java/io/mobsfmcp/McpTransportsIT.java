// SPDX-License-Identifier: MIT
package io.mobsfmcp;

import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real server with the MCP Java SDK client over both transports, backed by a
 * WireMock MobSF: Streamable HTTP in-process, stdio as a child JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "mobsf-mcp.auth-token=" + McpTransportsIT.TOKEN)
class McpTransportsIT {

	static final String TOKEN = "it-token";

	static final WireMockServer mobsf = MobsfStub.start();

	@LocalServerPort
	int port;

	@DynamicPropertySource
	static void mobsfProperties(DynamicPropertyRegistry registry) {
		registry.add("mobsf.url", mobsf::baseUrl);
		registry.add("mobsf.api-key", () -> MobsfStub.API_KEY);
	}

	@AfterAll
	static void stopMobsf() {
		mobsf.stop();
	}

	@Test
	void streamableHttp() {
		var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
			.endpoint("/mcp")
			.requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + TOKEN))
			.build();
		exercise(transport);
	}

	@Test
	void stdio() {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		var params = ServerParameters.builder(java)
			.args("-cp", System.getProperty("java.class.path"), MobsfMcpApplication.class.getName(),
					"--transport=stdio")
			.env(Map.of("MOBSF_URL", mobsf.baseUrl(), "MOBSF_API_KEY", MobsfStub.API_KEY, "MCP_TRANSPORT", "http"))
			.build();
		exercise(new StdioClientTransport(params, McpJsonDefaults.getMapper()));
	}

	private void exercise(McpClientTransport transport) {
		// The stdio case starts a cold JVM and a whole Spring context as a child process,
		// which took 13s on an idle machine here and longer while this test's own context
		// and a MobSF container are running. requestTimeout covers the initialize call, so
		// 30s made this a timing test rather than a transport test.
		try (McpSyncClient client = McpClient.sync(transport)
			.initializationTimeout(Duration.ofSeconds(180))
			.requestTimeout(Duration.ofSeconds(180))
			.build()) {
			assertThat(client.initialize().serverInfo().name()).isEqualTo("mobsf-mcp");

			assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
				.contains("mobsf_health", "list_scans");

			McpSchema.CallToolResult health = client.callTool(new McpSchema.CallToolRequest("mobsf_health", Map.of()));
			assertThat(text(health)).contains("\"ok\":true").contains("\"totalScans\":1").doesNotContain(MobsfStub.API_KEY);

			McpSchema.CallToolResult scans = client
				.callTool(new McpSchema.CallToolRequest("list_scans", Map.of("pageSize", 5)));
			assertThat(scans.isError()).isNotEqualTo(Boolean.TRUE);
			assertThat(text(scans)).contains("82ab8b2193b3cfb1c737e3a786be363a").contains("jakhar.aseem.diva");
		}
	}

	private static String text(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

}
