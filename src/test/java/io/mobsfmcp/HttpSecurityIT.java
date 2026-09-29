// SPDX-License-Identifier: MIT
package io.mobsfmcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** The http-mode guard rails: bearer token, Origin check, and the /uploads endpoint. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "mobsf-mcp.auth-token=" + HttpSecurityIT.TOKEN)
class HttpSecurityIT {

	static final String TOKEN = "security-it-token";

	static final WireMockServer mobsf = MobsfStub.start();

	@TempDir
	static Path workDir;

	@LocalServerPort
	int port;

	private final HttpClient http = HttpClient.newHttpClient();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("mobsf.url", mobsf::baseUrl);
		registry.add("mobsf.api-key", () -> MobsfStub.API_KEY);
		registry.add("mobsf.upload-dir", () -> workDir.resolve("apks").toString());
		registry.add("mobsf.report-dir", () -> workDir.resolve("reports").toString());
	}

	@AfterAll
	static void stopMobsf() {
		mobsf.stop();
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
	}

	private HttpResponse<String> send(HttpRequest request) throws Exception {
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void mcpEndpointRejectsMissingAndWrongTokens() throws Exception {
		assertThat(send(request("/mcp").POST(HttpRequest.BodyPublishers.ofString("{}")).build()).statusCode())
			.isEqualTo(401);

		assertThat(send(request("/mcp").header("Authorization", "Bearer not-the-token")
			.POST(HttpRequest.BodyPublishers.ofString("{}"))
			.build()).statusCode()).isEqualTo(401);
	}

	@Test
	void browserOriginsAreRejectedEvenWithAValidToken() throws Exception {
		HttpResponse<String> response = send(request("/mcp").header("Authorization", "Bearer " + TOKEN)
			.header("Origin", "http://evil.example")
			.POST(HttpRequest.BodyPublishers.ofString("{}"))
			.build());

		assertThat(response.statusCode()).isEqualTo(403);
	}

	@Test
	void healthEndpointStaysOpenForContainerProbes() throws Exception {
		assertThat(send(request("/actuator/health").GET().build()).statusCode()).isEqualTo(200);
	}

	@Test
	void uploadEndpointNeedsATokenAndThenReturnsAnUploadId() throws Exception {
		String boundary = "testboundary";
		byte[] body = multipart(boundary, "demo.apk", "fake-apk-bytes");

		HttpResponse<String> anonymous = send(request("/uploads")
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build());
		assertThat(anonymous.statusCode()).isEqualTo(401);

		HttpResponse<String> authorized = send(request("/uploads").header("Authorization", "Bearer " + TOKEN)
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build());

		assertThat(authorized.statusCode()).isEqualTo(200);
		assertThat(authorized.body()).contains("\"uploadId\"").contains("demo.apk").contains("upload_app");
	}

	@Test
	void reportDownloadsAreGuardedAndMissingReportsAre404() throws Exception {
		assertThat(send(request("/reports/abc123/static.json").GET().build()).statusCode()).isEqualTo(401);

		HttpResponse<String> missing = send(
				request("/reports/abc123/static.json").header("Authorization", "Bearer " + TOKEN).GET().build());
		assertThat(missing.statusCode()).isEqualTo(404);

		HttpResponse<String> traversal = send(request("/reports/abc123/..%2Fsecret.json")
			.header("Authorization", "Bearer " + TOKEN)
			.GET()
			.build());
		assertThat(traversal.statusCode()).isIn(400, 404);
	}

	private static byte[] multipart(String boundary, String fileName, String content) {
		String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
				+ "\"\r\nContent-Type: application/octet-stream\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n";
		return body.getBytes(StandardCharsets.UTF_8);
	}

}
