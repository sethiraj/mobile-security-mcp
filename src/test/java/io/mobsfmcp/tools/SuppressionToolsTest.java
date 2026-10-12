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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuppressionToolsTest {

	static final String HASH = "82ab8b2193b3cfb1c737e3a786be363a";

	WireMockServer mobsf;

	@BeforeEach
	void start() {
		mobsf = MobsfStub.start();
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/suppress_by_rule")).willReturn(okJson("{\"status\": \"ok\"}")));
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/suppress_by_files")).willReturn(okJson("{\"status\": \"ok\"}")));
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/delete_suppression")).willReturn(okJson("{\"status\": \"ok\"}")));
		// the rule ids a suppression is checked against come from the scan's own report
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/report_json")).willReturn(okJson("""
				{"manifest_analysis": {"manifest_findings": [
				   {"rule": "app_is_debuggable", "severity": "high"},
				   {"rule": "clear_text_traffic", "severity": "high"}]},
				 "code_analysis": {"findings": {
				   "android_insecure_random": {"metadata": {"severity": "warning"}},
				   "android_logging": {"metadata": {"severity": "info"}}}}}""")));
	}

	@AfterEach
	void stop() {
		mobsf.stop();
	}

	private SuppressionTools tools() {
		MobsfProperties properties = new MobsfProperties(mobsf.baseUrl(), MobsfStub.API_KEY, Path.of("/data/apks"),
				Path.of("/data/reports"), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30), 500);
		return new SuppressionTools(new MobsfClient(properties, RestClient.builder()));
	}

	@Test
	void suppressesARuleAcrossTheApp() {
		SuppressionTools.SuppressionResult result = tools().suppressFinding(HASH, "android_insecure_random", "code", null);

		assertThat(result.scope()).isEqualTo("rule");
		assertThat(result.status()).isEqualTo("ok");
		assertThat(result.nextStep()).contains("get_scorecard").contains("unsuppress_finding");
		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/suppress_by_rule"))
			.withRequestBody(containing("rule=android_insecure_random"))
			.withRequestBody(containing("type=code"))
			.withRequestBody(containing("hash=" + HASH)));
	}

	@Test
	void suppressesByFileWhenAskedTo() {
		tools().suppressFinding(HASH, "android_logging", "code", "file");

		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/suppress_by_files"))
			.withRequestBody(containing("rule=android_logging")));
		mobsf.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/suppress_by_rule")));
	}

	@Test
	void manifestFindingsCannotBeScopedToFiles() {
		assertThatThrownBy(() -> tools().suppressFinding(HASH, "clear_text_traffic", "manifest", "file"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("only applies to code rules");
	}

	@Test
	void onlyMobsfsTwoSuppressionTypesAreAccepted() {
		assertThatThrownBy(() -> tools().suppressFinding(HASH, "android_logging", "network", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'code' or 'manifest'");
		assertThatThrownBy(() -> tools().suppressFinding(HASH, "android_logging", null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> tools().suppressFinding(HASH, " ", "code", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'rule' is required");
	}

	@Test
	void unknownScopeIsRejected() {
		assertThatThrownBy(() -> tools().suppressFinding(HASH, "android_logging", "code", "everywhere"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Use 'rule' or 'file'");
	}

	@Test
	void listsWhatIsHiddenSoACleanReportCanBeTrusted() {
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/list_suppressions")).willReturn(okJson("""
				{"status": "ok", "message": [
				  {"id": 1, "PACKAGE_NAME": "com.example.app", "SUPPRESS_RULE_ID": ["app_is_debuggable"],
				   "SUPPRESS_FILES": {}, "SUPPRESS_TYPE": "manifest"},
				  {"id": 2, "PACKAGE_NAME": "com.example.app", "SUPPRESS_RULE_ID": [],
				   "SUPPRESS_FILES": {"android_logging": ["A.java", "B.java"]}, "SUPPRESS_TYPE": "code"}
				]}""")));

		SuppressionTools.Suppressions result = tools().listSuppressions(HASH);

		assertThat(result.count()).isEqualTo(2);
		assertThat(result.suppressions()).extracting(SuppressionTools.Suppression::rule)
			.containsExactly("app_is_debuggable", "android_logging");
		assertThat(result.suppressions().get(0).scope()).isEqualTo("rule");
		assertThat(result.suppressions().get(1).scope()).isEqualTo("file");
		assertThat(result.suppressions().get(1).files()).containsExactly("A.java", "B.java");
		assertThat(result.suppressions().get(0).packageName()).isEqualTo("com.example.app");
		assertThat(result.note()).contains("hidden from this app's reports");
	}

	@Test
	void anEmptyListSaysSoPlainly() {
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/list_suppressions"))
			.willReturn(okJson("{\"status\": \"ok\", \"message\": []}")));

		SuppressionTools.Suppressions result = tools().listSuppressions(HASH);

		assertThat(result.count()).isZero();
		assertThat(result.note()).contains("Nothing is suppressed");
	}

	@Test
	void unsuppressSendsTheKindMobsfExpects() {
		tools().unsuppressFinding(HASH, "android_insecure_random", "code", "file");

		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/delete_suppression"))
			.withRequestBody(containing("kind=file"))
			.withRequestBody(containing("type=code"))
			.withRequestBody(containing("rule=android_insecure_random")));
	}

	@Test
	void aRuleIdThatIsNotInTheScanIsRejectedInsteadOfSilentlyDoingNothing() {
		// MobSF stores any rule id and reports success, so a typo would suppress nothing
		assertThatThrownBy(() -> tools().suppressFinding(HASH, "android_debuggable", "manifest", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("No manifest finding in this scan uses the rule id")
			.hasMessageContaining("app_is_debuggable");
		mobsf.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/suppress_by_rule")));
	}

	@Test
	void suppressionProceedsWhenTheReportCannotBeRead() {
		// no report yet is not a reason to block the call; MobSF decides
		WireMockServer bare = MobsfStub.start();
		bare.stubFor(post(urlPathEqualTo("/api/v1/report_json"))
			.willReturn(aResponse().withStatus(404).withBody("{\"error\": \"not found\"}")));
		bare.stubFor(post(urlPathEqualTo("/api/v1/suppress_by_rule")).willReturn(okJson("{\"status\": \"ok\"}")));
		try {
			MobsfProperties properties = new MobsfProperties(bare.baseUrl(), MobsfStub.API_KEY, Path.of("/data/apks"),
					Path.of("/data/reports"), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30), 500);
			var tools = new SuppressionTools(new MobsfClient(properties, RestClient.builder()));

			assertThat(tools.suppressFinding(HASH, "anything_at_all", "code", null).status()).isEqualTo("ok");
		}
		finally {
			bare.stop();
		}
	}

	@Test
	void mobsfErrorsSurfaceWithTheirMessage() {
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/suppress_by_rule")).withRequestBody(containing("rule=clear_text_traffic"))
			.willReturn(aResponse().withStatus(500).withBody("{\"error\": \"Invalid Parameters\"}")));

		assertThatThrownBy(() -> tools().suppressFinding(HASH, "clear_text_traffic", "manifest", null))
			.hasMessageContaining("Invalid Parameters");
	}

}
