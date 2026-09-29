// SPDX-License-Identifier: MIT
package io.mobsfmcp.report;

import java.nio.charset.StandardCharsets;

import io.mobsfmcp.report.model.Finding;
import io.mobsfmcp.report.model.ScanReport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maps a report captured from a real MobSF 4.5.4 scan
 * ({@code src/test/resources/mobsf/source-scan-report.json}), so the mapper is tested
 * against MobSF's actual output rather than a hand-written guess.
 */
class StaticReportMapperTest {

	private final StaticReportMapper mapper = new StaticReportMapper(
			new MockEnvironment().withProperty("spring.ai.mcp.server.version", "0.3.0"));

	private ScanReport report() throws Exception {
		JsonNode raw = JsonMapper.builder()
			.build()
			.readTree(new String(new ClassPathResource("mobsf/source-scan-report.json").getContentAsByteArray(),
					StandardCharsets.UTF_8));
		return mapper.map("c78e07ebc3ee460055680240af74bd71", raw);
	}

	@Test
	void fillsEnvelopeAndAppInfo() throws Exception {
		ScanReport report = report();

		assertThat(report.schemaVersion()).isEqualTo("mobsf-mcp-report/v1");
		assertThat(report.scanType()).isEqualTo(ScanReport.ScanType.STATIC);
		assertThat(report.generatedAt()).isNotNull();
		assertThat(report.tool().name()).isEqualTo("mobsf-mcp");
		assertThat(report.tool().version()).isEqualTo("0.3.0");
		assertThat(report.app().hash()).isEqualTo("c78e07ebc3ee460055680240af74bd71");
		assertThat(report.app().fileName()).isEqualTo("vulndemo-src.zip");
		assertThat(report.app().platform()).isEqualTo("android");
		assertThat(report.dynamicAnalysis()).isNull();
	}

	@Test
	void summarisesScoreAndTrackers() throws Exception {
		ScanReport.Summary summary = report().summary();

		assertThat(summary.securityScore()).isEqualTo(30);
		assertThat(summary.findingCounts()).containsKeys("high", "warning", "info", "secure", "hotspot");
		assertThat(summary.findingCounts().get("high")).isPositive();
		assertThat(summary.trackersDetected()).isZero();
		assertThat(summary.trackersChecked()).isEqualTo(432);
	}

	@Test
	void mapsManifestFindings() throws Exception {
		assertThat(report().findings()).filteredOn(finding -> finding.category().equals("manifest"))
			.isNotEmpty()
			.allSatisfy(finding -> {
				assertThat(finding.id()).startsWith("static.manifest.");
				assertThat(finding.source()).isEqualTo("static");
				assertThat(finding.severity()).isIn("high", "warning", "info", "secure", "hotspot");
			})
			.anySatisfy(finding -> assertThat(finding.title()).contains("Clear text traffic"));
	}

	@Test
	void mapsCodeFindingsWithEvidenceAndStandards() throws Exception {
		Finding insecureRandom = report().findings()
			.stream()
			.filter(finding -> finding.id().equals("static.code.android_insecure_random"))
			.findFirst()
			.orElseThrow();

		assertThat(insecureRandom.category()).isEqualTo("code");
		assertThat(insecureRandom.severity()).isEqualTo("warning");   // MobSF's own rating for this rule
		assertThat(insecureRandom.evidence()).singleElement().satisfies(evidence -> {
			assertThat(evidence.file()).isEqualTo("com/example/vuln/MainActivity.java");
			assertThat(evidence.line()).isEqualTo("6");
		});
		assertThat(insecureRandom.standards().cwe()).contains("CWE-330");
		assertThat(insecureRandom.standards().owaspMobile()).contains("M5");
		assertThat(insecureRandom.standards().masvs()).isEqualTo("MSTG-CRYPTO-6");
		assertThat(insecureRandom.standards().cvss()).isEqualTo(7.5);
	}

	@Test
	void keepsStaticDetailAlongsideFindings() throws Exception {
		ScanReport.StaticDetails details = report().staticAnalysis();

		assertThat(details.permissions()).anySatisfy(permission -> {
			assertThat(permission.name()).startsWith("android.permission.");
			assertThat(permission.status()).isNotBlank();
		});
		assertThat(details.trackers()).isEmpty();
	}

	@Test
	void everyFindingHasTheFieldsDownstreamToolsRelyOn() throws Exception {
		assertThat(report().findings()).isNotEmpty().allSatisfy(finding -> {
			assertThat(finding.id()).isNotBlank();
			assertThat(finding.source()).isEqualTo("static");
			assertThat(finding.category()).isNotBlank();
			assertThat(finding.title()).isNotBlank();
			assertThat(finding.evidence()).isNotNull();
		});
	}

}
