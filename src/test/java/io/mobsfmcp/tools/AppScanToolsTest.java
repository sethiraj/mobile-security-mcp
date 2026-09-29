// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.mobsfmcp.MobsfStub;
import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.config.MobsfProperties;
import io.mobsfmcp.config.ScanIndexProperties;
import io.mobsfmcp.scans.ScanIndex;
import io.mobsfmcp.scans.ScanSummary;
import io.mobsfmcp.upload.FileInputResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** find_app, list_app_scans and the re-pointed search_scan. */
class AppScanToolsTest {

	static final String DEMO_2_0 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa20";

	static final String DEMO_1_0 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa10";

	static final String OTHER = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	WireMockServer mobsf;

	@BeforeEach
	void start() {
		mobsf = MobsfStub.start();
		MobsfStub.stubScanPages(mobsf,
				List.of(MobsfStub.scanRow(DEMO_2_0, "demo-2.0.apk", "Demo", "com.example.demo", "2.0", "apk", "t3"),
						MobsfStub.scanRow(DEMO_1_0, "demo-1.0.apk", "Demo", "com.example.demo", "1.0", "apk", "t2"),
						MobsfStub.scanRow(OTHER, "demo-notes.zip", null, null, null, "zip", "t1")),
				10);
	}

	@AfterEach
	void stop() {
		mobsf.stop();
	}

	private AppScanTools tools() {
		MobsfProperties properties = new MobsfProperties(mobsf.baseUrl(), MobsfStub.API_KEY, Path.of("/data/apks"),
				Path.of("/data/reports"), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30), 500);
		MobsfClient client = new MobsfClient(properties, RestClient.builder());
		ScanIndex index = new ScanIndex(client, new ScanIndexProperties(Duration.ofSeconds(60), 2000, 100));
		StaticAnalysisTools staticTools = new StaticAnalysisTools(client, properties,
				new FileInputResolver(properties), index);
		return new AppScanTools(index, client, staticTools);
	}

	@Test
	void findsAnAppWithEveryVersionNewestFirst() {
		AppScanTools.AppMatches result = tools().findApp("com.example.demo", null, null);

		assertThat(result.matchCount()).isEqualTo(1);
		assertThat(result.ambiguous()).isFalse();
		assertThat(result.apps()).singleElement().satisfies(app -> {
			assertThat(app.packageName()).isEqualTo("com.example.demo");
			assertThat(app.scanCount()).isEqualTo(2);
			assertThat(app.versions()).extracting(ScanSummary::version).containsExactly("2.0", "1.0");
		});
		assertThat(result.nextStep()).contains("compare_app_versions");
	}

	@Test
	void flagsAmbiguityWhenSeveralAppsMatch() {
		// "demo" matches both the package-named app and the unrelated zip
		AppScanTools.AppMatches result = tools().findApp("demo", null, null);

		assertThat(result.matchCount()).isEqualTo(2);
		assertThat(result.ambiguous()).isTrue();
		assertThat(result.nextStep()).contains("2 apps match").contains("packageName");
	}

	@Test
	void noMatchIsNotAnErrorAndSaysWhatToDoNext() {
		AppScanTools.AppMatches result = tools().findApp("com.nothing.here", null, null);

		assertThat(result.matchCount()).isZero();
		assertThat(result.apps()).isEmpty();
		assertThat(result.nextStep()).contains("list_scans").contains("upload_app");
	}

	@Test
	void findsByHash() {
		AppScanTools.AppMatches result = tools().findApp(DEMO_1_0, null, null);

		assertThat(result.matchCount()).isEqualTo(1);
		assertThat(result.apps().get(0).packageName()).isEqualTo("com.example.demo");
	}

	@Test
	void anUnknownHashAsksMobsfBeforeGivingUp() {
		String missing = "ffffffffffffffffffffffffffffffff";
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/search")).withRequestBody(containing(missing))
			.willReturn(aResponse().withStatus(404).withBody("{\"error\": \"Scan not found\"}")));

		AppScanTools.AppMatches result = tools().findApp(missing, null, null);

		assertThat(result.matchCount()).isZero();
		assertThat(result.nextStep()).contains("No scan with hash").contains("Upload and scan");
	}

	@Test
	void listsOneAppsScansAndCanAddScores() {
		MobsfStub.stubStaticAnalysis(mobsf, DEMO_2_0);

		AppScanTools.AppScans plain = tools().listAppScans("com.example.demo", false, null);
		assertThat(plain.scans()).extracting(AppScanTools.ScoredScan::hash).containsExactly(DEMO_2_0, DEMO_1_0);
		assertThat(plain.scans()).allSatisfy(scan -> assertThat(scan.securityScore()).isNull());
		assertThat(plain.nextStep()).contains("compare_app_versions");

		AppScanTools.AppScans scored = tools().listAppScans("com.example.demo", true, null);
		assertThat(scored.scans().get(0).securityScore()).isEqualTo(42);
		// the second scan has no scorecard stubbed: the error is reported per scan, not thrown
		assertThat(scored.scans().get(1).scoreError()).isNotBlank();
	}

	@Test
	void ambiguousOrMissingAppNamesAreRejectedWithGuidance() {
		assertThatThrownBy(() -> tools().listAppScans("demo", false, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("2 apps match")
			.hasMessageContaining("pass the hash");
		assertThatThrownBy(() -> tools().listAppScans("nothing-here", false, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("find_app");
		assertThatThrownBy(() -> tools().listAppScans(" ", false, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'app' is required");
	}

	@Test
	void searchScanReturnsMatchesInsteadOfAFullReportOr404() {
		AppScanTools.AppMatches result = tools().searchScan("demo", null);

		assertThat(result.matchCount()).isEqualTo(2);
		assertThat(result.apps()).hasSize(2);

		AppScanTools.AppMatches none = tools().searchScan("no-such-app", null);
		assertThat(none.matchCount()).isZero();
		assertThat(none.nextStep()).contains("list_scans");
	}

	@Test
	void comparesTheTwoMostRecentScansByDefault() {
		MobsfStub.stubStaticAnalysis(mobsf, DEMO_2_0);
		// the older scan plays the part of the "other" app in the shared stub
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/scorecard")).withRequestBody(containing("hash=" + DEMO_1_0))
			.willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("""
					{"security_score": 20, "app_name": "Demo", "file_name": "demo-1.0.apk", "version": "v4.5.4",
					 "total_trackers": 432, "trackers": 0,
					 "high": [{"title": "Debug Enabled For App"}, {"title": "Janus vulnerability"}],
					 "warning": [], "info": [], "secure": [], "hotspot": []}""")));

		AppScanTools.VersionComparison result = tools().compareAppVersions("com.example.demo", null, null, null);

		assertThat(result.fromVersion()).isEqualTo("1.0");     // older
		assertThat(result.toVersion()).isEqualTo("2.0");       // newer
		assertThat(result.fromHash()).isEqualTo(DEMO_1_0);
		assertThat(result.toHash()).isEqualTo(DEMO_2_0);
		assertThat(result.summary()).contains("improved by 22");
		assertThat(result.findings().onlyInA()).contains("Janus vulnerability");   // fixed in 2.0
		assertThat(result.findings().inBoth()).contains("Debug Enabled For App");
	}

	@Test
	void comparesExplicitVersionsAndReportsUnknownOnes() {
		MobsfStub.stubStaticAnalysis(mobsf, DEMO_2_0);
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/scorecard")).withRequestBody(containing("hash=" + DEMO_1_0))
			.willReturn(com.github.tomakehurst.wiremock.client.WireMock
				.okJson("{\"security_score\": 20, \"high\": [], \"warning\": [], \"info\": [], \"secure\": [],"
						+ " \"hotspot\": []}")));

		assertThat(tools().compareAppVersions("com.example.demo", "1.0", "2.0", null).toHash()).isEqualTo(DEMO_2_0);

		assertThatThrownBy(() -> tools().compareAppVersions("com.example.demo", "0.9", "2.0", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("No scan of com.example.demo has version '0.9'")
			.hasMessageContaining("Known versions: 2.0, 1.0");
	}

	@Test
	void refusesComparisonsThatMakeNoSense() {
		assertThatThrownBy(() -> tools().compareAppVersions("demo-notes.zip", null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Only one scan exists")
			.hasMessageContaining("Scan another build");

		assertThatThrownBy(() -> tools().compareAppVersions("com.example.demo", "2.0", "2.0", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("same scan");
	}

	@Test
	void refusesToCompareDifferentScanTypes() {
		// an apk and a source zip grouped under one name would be meaningless to diff
		WireMockServer mixed = MobsfStub.start();
		MobsfStub.stubScanPages(mixed,
				List.of(MobsfStub.scanRow(DEMO_2_0, "demo.apk", "Demo", "com.example.mixed", "2.0", "apk", "t2"),
						MobsfStub.scanRow(DEMO_1_0, "demo-src.zip", "Demo", "com.example.mixed", "1.0", "zip", "t1")),
				10);
		try {
			MobsfProperties properties = new MobsfProperties(mixed.baseUrl(), MobsfStub.API_KEY, Path.of("/data/apks"),
					Path.of("/data/reports"), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30),
					500);
			MobsfClient client = new MobsfClient(properties, RestClient.builder());
			ScanIndex index = new ScanIndex(client, new ScanIndexProperties(Duration.ofSeconds(60), 2000, 100));
			AppScanTools tools = new AppScanTools(index, client,
					new StaticAnalysisTools(client, properties, new FileInputResolver(properties), index));

			assertThatThrownBy(() -> tools.compareAppVersions("com.example.mixed", null, null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Refusing to compare a zip scan with a apk scan");
		}
		finally {
			mixed.stop();
		}
	}

	@Test
	void blankMobsfFieldsArriveAsNull() {
		AppScanTools.AppMatches result = tools().findApp("demo-notes.zip", null, null);

		ScanSummary zip = result.apps().get(0).versions().get(0);
		assertThat(zip.appName()).isNull();
		assertThat(zip.packageName()).isNull();
		assertThat(zip.version()).isNull();
		assertThat(result.apps().get(0).appKey()).isEqualTo("demo-notes.zip");
	}

}
