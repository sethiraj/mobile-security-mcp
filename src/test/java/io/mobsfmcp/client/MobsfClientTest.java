// SPDX-License-Identifier: MIT
package io.mobsfmcp.client;

import java.nio.file.Path;
import java.time.Duration;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.mobsfmcp.MobsfStub;
import io.mobsfmcp.config.MobsfProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MobsfClientTest {

	static WireMockServer mobsf;

	@BeforeAll
	static void start() {
		mobsf = MobsfStub.start();
	}

	@AfterAll
	static void stop() {
		mobsf.stop();
	}

	static MobsfClient client(String url, String apiKey) {
		return new MobsfClient(new MobsfProperties(url, apiKey, Path.of("/data/apks"), Path.of("/data/reports"),
				Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30), 500), RestClient.builder());
	}

	@Test
	void listsScansWithApiKeyHeaderAndPaging() {
		JsonNode body = client(mobsf.baseUrl() + "/", MobsfStub.API_KEY).recentScans(2, 5);

		assertThat(body.path("count").asInt()).isEqualTo(1);
		assertThat(body.path("content").get(0).path("MD5").asString()).isEqualTo("82ab8b2193b3cfb1c737e3a786be363a");
		mobsf.verify(getRequestedFor(urlPathEqualTo("/api/v1/scans"))
			.withQueryParam("page", equalTo("2"))
			.withQueryParam("page_size", equalTo("5")));
	}

	@Test
	void wrongApiKeyIsUnauthorized() {
		mobsf.stubFor(get(urlPathEqualTo("/api/v1/scans"))
			.withHeader("X-Mobsf-Api-Key", equalTo("wrong"))
			.willReturn(aResponse().withStatus(401).withBody("{\"error\":\"You are unauthorized to make this request.\"}")));

		assertThatThrownBy(() -> client(mobsf.baseUrl(), "wrong").recentScans(1, 1)).isInstanceOf(MobsfException.class)
			.satisfies(ex -> assertThat(((MobsfException) ex).kind()).isEqualTo(MobsfException.Kind.UNAUTHORIZED))
			.hasMessageContaining("MOBSF_API_KEY")
			.hasMessageNotContaining("wrong");
	}

	@Test
	void missingApiKeyFailsBeforeCallingMobsf() {
		assertThatThrownBy(() -> client(mobsf.baseUrl(), " ").recentScans(1, 1)).isInstanceOf(MobsfException.class)
			.satisfies(ex -> assertThat(((MobsfException) ex).kind()).isEqualTo(MobsfException.Kind.NOT_CONFIGURED));
	}

	@Test
	void unreachableMobsfExplainsDockerNetworking() {
		assertThatThrownBy(() -> client("http://127.0.0.1:9", MobsfStub.API_KEY).recentScans(1, 1))
			.isInstanceOf(MobsfException.class)
			.satisfies(ex -> assertThat(((MobsfException) ex).kind()).isEqualTo(MobsfException.Kind.UNREACHABLE))
			.hasMessageContaining("host.docker.internal");
	}

	@Test
	void serverErrorIsReportedWithStatus() {
		mobsf.stubFor(get(urlPathEqualTo("/api/v1/scans"))
			.withHeader("X-Mobsf-Api-Key", equalTo("boom"))
			.willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

		assertThatThrownBy(() -> client(mobsf.baseUrl(), "boom").recentScans(1, 1)).isInstanceOf(MobsfException.class)
			.satisfies(ex -> assertThat(((MobsfException) ex).kind()).isEqualTo(MobsfException.Kind.SERVER_ERROR))
			.hasMessageContaining("HTTP 500");
	}

}
