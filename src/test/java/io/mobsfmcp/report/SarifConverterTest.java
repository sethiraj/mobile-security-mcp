// SPDX-License-Identifier: MIT
package io.mobsfmcp.report;

import java.nio.charset.StandardCharsets;

import io.mobsfmcp.report.model.ScanReport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** SARIF 2.1.0 output, built from the report captured from a real MobSF scan. */
class SarifConverterTest {

	private final SarifConverter converter = new SarifConverter();

	private ObjectNode sarif() throws Exception {
		JsonNode raw = JsonMapper.builder()
			.build()
			.readTree(new String(new ClassPathResource("mobsf/source-scan-report.json").getContentAsByteArray(),
					StandardCharsets.UTF_8));
		ScanReport report = new StaticReportMapper(
				new MockEnvironment().withProperty("spring.ai.mcp.server.version", "0.9.0"))
			.map("c78e07ebc3ee460055680240af74bd71", raw);
		return converter.convert(report);
	}

	@Test
	void hasTheEnvelopeConsumersRequire() throws Exception {
		ObjectNode sarif = sarif();

		assertThat(sarif.path("version").asString()).isEqualTo("2.1.0");
		assertThat(sarif.path("$schema").asString()).contains("sarif-2.1.0");
		assertThat(sarif.path("runs")).hasSize(1);
		assertThat(sarif.path("runs").get(0).path("tool").path("driver").path("name").asString()).isEqualTo("MobSF");
	}

	@Test
	void everyResultReferencesADeclaredRuleAndHasALocation() throws Exception {
		JsonNode run = sarif().path("runs").get(0);

		var ruleIds = run.path("tool").path("driver").path("rules").valueStream().map(r -> r.path("id").asString())
			.toList();
		assertThat(ruleIds).isNotEmpty().doesNotHaveDuplicates();

		assertThat(run.path("results").isEmpty()).isFalse();
		run.path("results").forEach(result -> {
			assertThat(ruleIds).contains(result.path("ruleId").asString());
			assertThat(result.path("level").asString()).isIn("error", "warning", "note", "none");
			assertThat(result.path("message").path("text").asString()).isNotBlank();
			// GitHub only shows alerts that have a location
			assertThat(result.path("locations").isEmpty()).isFalse();
			assertThat(result.path("locations").get(0).path("physicalLocation").path("artifactLocation").path("uri")
				.asString()).isNotBlank();
		});
	}

	@Test
	void codeFindingsKeepFileAndLine() throws Exception {
		JsonNode result = sarif().path("runs")
			.get(0)
			.path("results")
			.valueStream()
			.filter(r -> r.path("ruleId").asString().equals("static.code.android_insecure_random"))
			.findFirst()
			.orElseThrow();

		JsonNode physical = result.path("locations").get(0).path("physicalLocation");
		assertThat(physical.path("artifactLocation").path("uri").asString())
			.isEqualTo("com/example/vuln/MainActivity.java");
		assertThat(physical.path("region").path("startLine").asInt()).isEqualTo(6);
	}

	@Test
	void standardsBecomeTagsAndSeverity() throws Exception {
		JsonNode rule = sarif().path("runs")
			.get(0)
			.path("tool")
			.path("driver")
			.path("rules")
			.valueStream()
			.filter(r -> r.path("id").asString().equals("static.code.android_insecure_random"))
			.findFirst()
			.orElseThrow();

		assertThat(rule.path("properties").path("security-severity").asString()).isEqualTo("7.5");
		assertThat(rule.path("properties").path("masvs").asString()).isEqualTo("MSTG-CRYPTO-6");
		var tags = rule.path("properties").path("tags").valueStream().map(JsonNode::asString).toList();
		assertThat(tags).contains("security", "code", "static").anyMatch(tag -> tag.startsWith("external/cwe/cwe-330"));
		assertThat(rule.path("helpUri").asString()).startsWith("https://");
	}

	@Test
	void severityMapsToSarifLevels() {
		assertThat(SarifConverter.level("high")).isEqualTo("error");
		assertThat(SarifConverter.level("warning")).isEqualTo("warning");
		assertThat(SarifConverter.level("info")).isEqualTo("note");
		assertThat(SarifConverter.level("secure")).isEqualTo("none");
		assertThat(SarifConverter.level("hotspot")).isEqualTo("none");
		assertThat(SarifConverter.level(null)).isEqualTo("warning");
	}

	@Test
	void runCarriesTheAppAndScore() throws Exception {
		JsonNode properties = sarif().path("runs").get(0).path("properties");

		assertThat(properties.path("appHash").asString()).isEqualTo("c78e07ebc3ee460055680240af74bd71");
		assertThat(properties.path("packageName").asString()).isEqualTo("com.example.vuln");
		assertThat(properties.path("mobsfSecurityScore").asInt()).isEqualTo(30);
		assertThat(properties.path("scanType").asString()).isEqualTo("static");
	}

}
