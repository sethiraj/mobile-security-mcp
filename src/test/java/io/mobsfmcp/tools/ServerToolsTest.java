// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.nio.file.Path;
import java.time.Duration;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.mobsfmcp.MobsfStub;
import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.config.MobsfProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ServerToolsTest {

	static WireMockServer mobsf;

	@BeforeAll
	static void start() {
		mobsf = MobsfStub.start();
	}

	@AfterAll
	static void stop() {
		mobsf.stop();
	}

	static ServerTools tools(String url, String apiKey) {
		var properties = new MobsfProperties(url, apiKey, Path.of("/data/apks"), Path.of("/data/reports"),
				Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30), 500);
		return new ServerTools(new MobsfClient(properties, RestClient.builder()), properties);
	}

	@Test
	void healthReportsOkAndMasksKey() {
		ServerTools.HealthStatus status = tools(mobsf.baseUrl(), MobsfStub.API_KEY).health();

		assertThat(status.ok()).isTrue();
		assertThat(status.totalScans()).isEqualTo(1);
		assertThat(status.apiKey()).isEqualTo("****1234");
		assertThat(status.error()).isNull();
	}

	@Test
	void healthReportsProblemInsteadOfThrowing() {
		ServerTools.HealthStatus status = tools(mobsf.baseUrl(), null).health();

		assertThat(status.ok()).isFalse();
		assertThat(status.apiKeyConfigured()).isFalse();
		assertThat(status.errorKind()).isEqualTo("NOT_CONFIGURED");
		assertThat(status.error()).contains("MOBSF_API_KEY");
	}

	@Test
	void listScansMapsEntriesAndClampsPaging() {
		ServerTools.ScanPage page = tools(mobsf.baseUrl(), MobsfStub.API_KEY).listScans(0, 500);

		assertThat(page.page()).isEqualTo(1);
		assertThat(page.pageSize()).isEqualTo(ServerTools.MAX_PAGE_SIZE);
		assertThat(page.totalScans()).isEqualTo(1);
		assertThat(page.scans()).singleElement().satisfies(scan -> {
			assertThat(scan.hash()).isEqualTo("82ab8b2193b3cfb1c737e3a786be363a");
			assertThat(scan.packageName()).isEqualTo("jakhar.aseem.diva");
			assertThat(scan.scanType()).isEqualTo("apk");
		});
		mobsf.verify(getRequestedFor(urlPathEqualTo("/api/v1/scans")).withQueryParam("page_size", equalTo("100")));
	}

}
