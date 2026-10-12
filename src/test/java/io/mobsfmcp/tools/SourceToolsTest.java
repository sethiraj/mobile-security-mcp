// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.nio.file.Path;
import java.time.Duration;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.mobsfmcp.MobsfStub;
import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.config.MobsfProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceToolsTest {

	static final String HASH = "82ab8b2193b3cfb1c737e3a786be363a";

	WireMockServer mobsf;

	@BeforeEach
	void start() {
		mobsf = MobsfStub.start();
	}

	@AfterEach
	void stop() {
		mobsf.stop();
	}

	private SourceTools tools() {
		MobsfProperties properties = new MobsfProperties(mobsf.baseUrl(), MobsfStub.API_KEY, Path.of("/data/apks"),
				Path.of("/data/reports"), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30), 500);
		return new SourceTools(new MobsfClient(properties, RestClient.builder()));
	}

	private void stubSource(String content) {
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/view_source"))
			.willReturn(okJson("{\"title\": \"View Source\", \"file\": \"MainActivity.java\", \"data\": \""
					+ content.replace("\"", "\\\"").replace("\n", "\\n") + "\", \"type\": \"java\"}")));
	}

	@Test
	void returnsTheFileBehindAFinding() {
		stubSource("package com.example.vuln;\n\nclass MainActivity {\n  Random r = new Random();\n}");

		SourceTools.SourceFile source = tools().viewSource(HASH, "com/example/vuln/MainActivity.java", "java");

		assertThat(source.source()).contains("new Random()");
		assertThat(source.lineCount()).isEqualTo(5);
		assertThat(source.truncated()).isFalse();
		assertThat(source.note()).isNull();
		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/view_source"))
			.withRequestBody(containing("file=com%2Fexample%2Fvuln%2FMainActivity.java"))
			.withRequestBody(containing("type=java")));
	}

	@Test
	void defaultsToJavaWhichIsWhatApkFindingsUse() {
		stubSource("class A {}");

		assertThat(tools().viewSource(HASH, "A.java", null).type()).isEqualTo("java");
		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/view_source")).withRequestBody(containing("type=java")));
	}

	@Test
	void longFilesAreTruncatedRatherThanFloodingTheAgent() {
		stubSource("x".repeat(SourceTools.MAX_CHARS + 500));

		SourceTools.SourceFile source = tools().viewSource(HASH, "Big.java", "java");

		assertThat(source.truncated()).isTrue();
		assertThat(source.source()).hasSize(SourceTools.MAX_CHARS);
		assertThat(source.note()).contains("Truncated");
	}

	@Test
	void anEmptyResponseExplainsTheLikelyCause() {
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/view_source")).willReturn(okJson("{\"data\": \"\"}")));

		SourceTools.SourceFile source = tools().viewSource(HASH, "Missing.java", "java");

		assertThat(source.source()).isEmpty();
		assertThat(source.note()).contains("Check the file path").contains("source zip");
	}

	@Test
	void hashAndFileAreRequired() {
		assertThatThrownBy(() -> tools().viewSource(" ", "A.java", "java"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'hash' is required");
		assertThatThrownBy(() -> tools().viewSource(HASH, null, "java"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("evidence.file");
	}

}
