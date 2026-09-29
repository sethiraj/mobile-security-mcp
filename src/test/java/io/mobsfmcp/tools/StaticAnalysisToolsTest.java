// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.mobsfmcp.MobsfStub;
import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.config.MobsfProperties;
import io.mobsfmcp.config.ScanIndexProperties;
import io.mobsfmcp.scans.ScanIndex;
import io.mobsfmcp.upload.FileInputResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticAnalysisToolsTest {

	static final String HASH = "82ab8b2193b3cfb1c737e3a786be363a";

	static final String ASYNC_HASH = "99999999999999999999999999999999";

	static WireMockServer mobsf;

	@TempDir
	Path workDir;

	@BeforeAll
	static void start() {
		mobsf = MobsfStub.start();
		MobsfStub.stubStaticAnalysis(mobsf, HASH);
	}

	@AfterAll
	static void stop() {
		mobsf.stop();
	}

	private StaticAnalysisTools tools() {
		Path uploads = workDir.resolve("apks");
		Path reports = workDir.resolve("reports");
		MobsfProperties properties = new MobsfProperties(mobsf.baseUrl(), MobsfStub.API_KEY, uploads, reports,
				Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30), 500);
		MobsfClient client = new MobsfClient(properties, RestClient.builder());
		return new StaticAnalysisTools(client, properties, new FileInputResolver(properties),
				new ScanIndex(client, new ScanIndexProperties(Duration.ofSeconds(60), 2000, 100)));
	}

	@Test
	void uploadReturnsHashAndPointsAtTheNextStep() throws Exception {
		Path apk = workDir.resolve("apks/diva-beta.apk");
		Files.createDirectories(apk.getParent());
		Files.writeString(apk, "fake-apk");

		StaticAnalysisTools.UploadResult result = tools().uploadApp(apk.toString(), null, null, null, null);

		assertThat(result.hash()).isEqualTo(HASH);
		assertThat(result.scanType()).isEqualTo("apk");
		assertThat(result.nextStep()).contains("scan_app");
		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/upload"))
			.withRequestBodyPart(com.github.tomakehurst.wiremock.client.WireMock.aMultipart("file")
				.withBody(containing("fake-apk"))
				.build()));
	}

	@Test
	void scanWaitsForCompletionAndSummarisesTheScorecard() {
		StaticAnalysisTools.ScanResult result = tools().scanApp(HASH, false, 60);

		assertThat(result.status()).isEqualTo("completed");
		assertThat(result.securityScore()).isEqualTo(42);
		assertThat(result.findingCounts()).isNotEmpty();
		assertThat(result.findingCounts()).containsEntry("high", 2).containsEntry("warning", 1);
		assertThat(result.nextStep()).contains("get_report");
	}

	@Test
	void scanThatOutlastsTheWaitReportsRunningInsteadOfFailing() {
		StaticAnalysisTools.ScanResult result = tools().scanApp(MobsfStub.SLOW_HASH, false, 1);

		assertThat(result.status()).isEqualTo("running");
		assertThat(result.securityScore()).isNull();
		assertThat(result.nextStep()).contains("scan_status");
	}

	@Test
	void scanStatusReportsProgress() {
		StaticAnalysisTools.ScanProgress progress = tools().scanStatus(HASH);

		assertThat(progress.status()).isEqualTo("completed");
		assertThat(progress.lastStep()).contains("Saving to Database");
		assertThat(progress.recentSteps()).isNotEmpty();
	}

	@Test
	void reportIsSlicedBySection() {
		JsonNode manifest = tools().getReport(HASH, "manifest");

		assertThat(manifest.has("manifest_analysis")).isTrue();
		assertThat(manifest.has("code_analysis")).isFalse();
		assertThat(tools().getReport(HASH, null).has("package_name")).isTrue();
		assertThat(tools().getReport(HASH, "all").has("code_analysis")).isTrue();
		assertThatThrownBy(() -> tools().getReport(HASH, "everything"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Unknown section");
	}

	@Test
	void pdfIsWrittenToTheReportDirectory() {
		StaticAnalysisTools.PdfReport pdf = tools().downloadPdf(HASH);

		assertThat(Path.of(pdf.path())).exists().hasFileName(HASH + ".pdf");
		assertThat(pdf.sizeBytes()).isPositive();
	}

	@Test
	void comparingTwoScansShowsScoreDeltaAndDifferingFindings() {
		StaticAnalysisTools.ScanComparison comparison = tools().compareScans(HASH, MobsfStub.OTHER_HASH);

		assertThat(comparison.scoreDelta()).isEqualTo(28);
		assertThat(comparison.onlyInA()).containsExactly("Debug Enabled For App");
		assertThat(comparison.inBoth()).containsExactly("App uses an insecure random generator");
	}

	@Test
	void deleteReportsWhatMobsfConfirmed() {
		StaticAnalysisTools.DeleteResult result = tools().deleteScan(HASH);

		assertThat(result.deleted()).isTrue();
		assertThat(result.detail()).isEqualTo("scan deleted");
	}

	@Test
	void scorecardSeparatesTrackersFoundFromTrackersChecked() {
		StaticAnalysisTools.Scorecard card = tools().getScorecard(HASH);

		assertThat(card.securityScore()).isEqualTo(42);
		assertThat(card.trackersDetected()).isEqualTo(3);     // found in this app
		assertThat(card.trackersChecked()).isEqualTo(432);    // size of MobSF's tracker database
		assertThat(card.mobsfVersion()).isEqualTo("v4.5.4");
		assertThat(card.highSeverityTitles()).contains("Debug Enabled For App");
	}

	@Test
	void hashIsRequired() {
		assertThatThrownBy(() -> tools().getScorecard(" ")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'hash' is required");
	}

	@Test
	void unknownScanSurfacesMobsfsMessage() {
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/scorecard")).withRequestBody(containing("hash=deadbeef"))
			.willReturn(aResponse().withStatus(404).withBody("{\"error\":\"Scan hash not found\"}")));

		assertThatThrownBy(() -> tools().getScorecard("deadbeef")).hasMessageContaining("Scan hash not found");
	}

	@Test
	void asyncMobsfIsRejectedInsteadOfLookingForAReportThatDoesNotExist() {
		// with MOBSF_ASYNC_ANALYSIS=1 MobSF queues the scan and answers with a task id
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/scan")).withRequestBody(containing("hash=" + ASYNC_HASH))
			.willReturn(okJson("{\"task_id\": \"d29f1b\", \"message\": \"Scan Queued with ID: d29f1b\"}")));

		assertThatThrownBy(() -> tools().scanApp(ASYNC_HASH, false, 30)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("async analysis mode")
			.hasMessageContaining("MOBSF_ASYNC_ANALYSIS=0")
			.hasMessageContaining("d29f1b");
	}

}
