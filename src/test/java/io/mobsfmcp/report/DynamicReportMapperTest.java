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
 * The dynamic report is shaped after MobSF 4.5.4's {@code dynamic_report} context keys,
 * read from the running container. It could not be produced from a live run here, because
 * dynamic analysis needs an attached Android device.
 */
class DynamicReportMapperTest {

	private static final String DYNAMIC_REPORT = """
			{"hash": "82ab8b2193b3cfb1c737e3a786be363a", "package": "com.example.vuln", "version": "v4.5.4",
			 "tls_tests": {"tls_misconfigured": {"status": "high", "description": "Cleartext traffic permitted"},
			               "tls_pinning_missing": {"status": "high", "description": "No certificate pinning"},
			               "tls_ok": {"status": "info", "description": "TLS 1.2 in use"}},
			 "apimon": ["javax.crypto.Cipher.getInstance"],
			 "urls": ["http://api.example.com/v1"],
			 "clipboard": ["password123"],
			 "sqlite": ["/data/data/com.example.vuln/databases/creds.db"],
			 "xml": ["/data/data/com.example.vuln/shared_prefs/creds.xml"], "others": [],
			 "droidmon": ["crypto"], "screenshots": ["screen1.png"]}""";

	private final MockEnvironment environment = new MockEnvironment().withProperty("spring.ai.mcp.server.version",
			"0.5.0");

	private final DynamicReportMapper mapper = new DynamicReportMapper(environment);

	private final StaticReportMapper staticMapper = new StaticReportMapper(environment);

	private static JsonNode parse(String json) {
		return JsonMapper.builder().build().readTree(json);
	}

	private ScanReport dynamicReport() {
		return mapper.map("82ab8b2193b3cfb1c737e3a786be363a", parse(DYNAMIC_REPORT));
	}

	@Test
	void mapsTlsResultsToFindings() {
		ScanReport report = dynamicReport();

		assertThat(report.scanType()).isEqualTo(ScanReport.ScanType.DYNAMIC);
		assertThat(report.app().packageName()).isEqualTo("com.example.vuln");
		assertThat(report.tool().mobsfVersion()).isEqualTo("v4.5.4");
		assertThat(report.findings()).filteredOn(finding -> finding.category().equals("tls"))
			.anySatisfy(finding -> {
				assertThat(finding.id()).isEqualTo("dynamic.tls.tls_pinning_missing");
				assertThat(finding.severity()).isEqualTo("high");
				assertThat(finding.source()).isEqualTo("dynamic");
			})
			.anySatisfy(finding -> assertThat(finding.severity()).isEqualTo("info"));
	}

	@Test
	void flagsClipboardUse() {
		assertThat(dynamicReport().findings()).anySatisfy(finding -> {
			assertThat(finding.id()).isEqualTo("dynamic.runtime.clipboard");
			assertThat(finding.severity()).isEqualTo("warning");
			assertThat(finding.description()).contains("password123");
		});
	}

	@Test
	void collectsRuntimeArtefacts() {
		ScanReport.DynamicDetails details = dynamicReport().dynamicAnalysis();

		assertThat(details.apiMonitor()).contains("javax.crypto.Cipher.getInstance");
		assertThat(details.httpTraffic()).contains("http://api.example.com/v1");
		assertThat(details.fileSystem()).contains("/data/data/com.example.vuln/databases/creds.db",
				"/data/data/com.example.vuln/shared_prefs/creds.xml");
		assertThat(details.screenshots()).contains("screen1.png");
		assertThat(details.tlsTests()).containsKey("tls_pinning_missing");
	}

	@Test
	void combinedReportMergesBothScansAndDeduplicates() throws Exception {
		JsonNode rawStatic = parse(new String(
				new ClassPathResource("mobsf/source-scan-report.json").getContentAsByteArray(), StandardCharsets.UTF_8));
		ScanReport staticReport = staticMapper.map("c78e07ebc3ee460055680240af74bd71", rawStatic);
		ScanReport dynamicReport = dynamicReport();

		ScanReport combined = mapper.combine(staticReport, dynamicReport);

		assertThat(combined.scanType()).isEqualTo(ScanReport.ScanType.COMBINED);
		assertThat(combined.summary().securityScore()).isEqualTo(staticReport.summary().securityScore());
		assertThat(combined.staticAnalysis()).isNotNull();
		assertThat(combined.dynamicAnalysis()).isNotNull();
		assertThat(combined.findings()).hasSize(staticReport.findings().size() + dynamicReport.findings().size());
		assertThat(combined.findings()).extracting(Finding::id).doesNotHaveDuplicates();
		assertThat(combined.findings()).extracting(Finding::source).contains("static", "dynamic");
		assertThat(combined.summary().findingCounts().get("high"))
			.isEqualTo((int) combined.findings().stream().filter(f -> "high".equals(f.severity())).count());
	}

}
