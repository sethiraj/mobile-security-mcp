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
 * Mapped from {@code src/test/resources/mobsf/dynamic-scan-report.json}, captured from a
 * real MobSF 4.5.4 dynamic run: the Selendroid test app on an API 30 emulator, with the
 * activity tester, Frida api_monitor and the TLS tests exercised.
 * <p>
 * The first version of this mapper was written by reading MobSF's source, and guessed
 * several shapes wrong — {@code tls_tests} is a flat object of booleans rather than
 * objects, {@code urls} are plain strings, and the screenshots live in
 * {@code activity_tester} keyed by file name while {@code screenshots} stays empty.
 */
class DynamicReportMapperTest {

	private final MockEnvironment environment = new MockEnvironment().withProperty("spring.ai.mcp.server.version",
			"1.0.0-beta.2");

	private final DynamicReportMapper mapper = new DynamicReportMapper(environment);

	private final StaticReportMapper staticMapper = new StaticReportMapper(environment);

	private static JsonNode load(String name) throws Exception {
		return JsonMapper.builder()
			.build()
			.readTree(new String(new ClassPathResource("mobsf/" + name).getContentAsByteArray(),
					StandardCharsets.UTF_8));
	}

	private ScanReport dynamicReport() throws Exception {
		return mapper.map("0336c0007f9e23412c8a131999232cc4", load("dynamic-scan-report.json"));
	}

	@Test
	void fillsTheEnvelopeFromARealRun() throws Exception {
		ScanReport report = dynamicReport();

		assertThat(report.schemaVersion()).isEqualTo("mobsf-mcp-report/v1");
		assertThat(report.scanType()).isEqualTo(ScanReport.ScanType.DYNAMIC);
		assertThat(report.app().packageName()).isEqualTo("io.selendroid.testapp");
		assertThat(report.app().platform()).isEqualTo("android");
		assertThat(report.tool().mobsfVersion()).isEqualTo("v4.5.4");
		assertThat(report.staticAnalysis()).isNull();
		assertThat(report.dynamicAnalysis()).isNotNull();
	}

	@Test
	void cleartextTrafficIsReportedAsAFailure() throws Exception {
		// MobSF names each test after the problem: true means the problem is present.
		// Selendroid really does send cleartext, so this is the one failing TLS test.
		Finding cleartext = dynamicReport().findings()
			.stream()
			.filter(f -> f.id().equals("dynamic.tls.has_cleartext"))
			.findFirst()
			.orElseThrow();

		assertThat(cleartext.severity()).isEqualTo("high");
		assertThat(cleartext.title()).isEqualTo("App sends cleartext (unencrypted) traffic");
		assertThat(cleartext.description()).contains("read or modified in transit");
		assertThat(cleartext.remediation()).contains("usesCleartextTraffic=false");
		assertThat(cleartext.source()).isEqualTo("dynamic");
		assertThat(cleartext.category()).isEqualTo("tls");
	}

	@Test
	void testsThatPassedAreRecordedAsSecureRatherThanUnknown() throws Exception {
		assertThat(dynamicReport().findings()).filteredOn(f -> f.category().equals("tls"))
			.hasSize(4)
			.filteredOn(f -> f.severity().equals("secure"))
			.hasSize(3)
			.allSatisfy(f -> assertThat(f.description()).isEqualTo("The app passed this TLS check."));
	}

	@Test
	void countsSeveritiesForTheSummary() throws Exception {
		ScanReport.Summary summary = dynamicReport().summary();

		assertThat(summary.findingCounts()).containsEntry("high", 1).containsEntry("secure", 3);
		assertThat(summary.securityScore()).isNull();   // MobSF scores static analysis only
	}

	@Test
	void keepsTheRuntimeArtefactsAnAgentCanAskAbout() throws Exception {
		ScanReport.DynamicDetails details = dynamicReport().dynamicAnalysis();

		// urls are plain strings in the real report, not objects
		assertThat(details.httpTraffic()).contains("https://www.google.com/generate_204")
			.anySatisfy(url -> assertThat(url).startsWith("file:///"));
		// domains carry reputation metadata; the names are what an agent needs
		assertThat(details.domains()).contains("play.googleapis.com", "www.google.com");
		// screenshots come from activity_tester, keyed by image, valued by activity
		assertThat(details.screenshots()).anySatisfy(shot -> assertThat(shot).contains("act-1.png")
			.contains("HomeScreenActivity"));
		assertThat(details.tlsTests()).containsKeys("has_cleartext", "tls_misconfigured");
	}

	@Test
	void emptySectionsDoNotBecomeFindings() throws Exception {
		// this run captured no clipboard writes, no sqlite/xml artefacts and no api monitor
		ScanReport report = dynamicReport();

		assertThat(report.findings()).noneMatch(f -> f.id().equals("dynamic.runtime.clipboard"));
		assertThat(report.dynamicAnalysis().fileSystem()).isEmpty();
		assertThat(report.dynamicAnalysis().apiMonitor()).isEmpty();
	}

	@Test
	void combinedReportMergesStaticAndDynamicFindings() throws Exception {
		ScanReport staticReport = staticMapper.map("c78e07ebc3ee460055680240af74bd71",
				load("source-scan-report.json"));
		ScanReport dynamicReport = dynamicReport();

		ScanReport combined = mapper.combine(staticReport, dynamicReport);

		assertThat(combined.scanType()).isEqualTo(ScanReport.ScanType.COMBINED);
		assertThat(combined.summary().securityScore()).isEqualTo(staticReport.summary().securityScore());
		assertThat(combined.staticAnalysis()).isNotNull();
		assertThat(combined.dynamicAnalysis()).isNotNull();
		assertThat(combined.findings()).extracting(Finding::id).doesNotHaveDuplicates();
		assertThat(combined.findings()).extracting(Finding::source).contains("static", "dynamic");
		assertThat(combined.summary().findingCounts().get("high"))
			.isEqualTo((int) combined.findings().stream().filter(f -> "high".equals(f.severity())).count());
	}

}
