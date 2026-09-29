// SPDX-License-Identifier: MIT
package io.mobsfmcp.scans;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.mobsfmcp.MobsfStub;
import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.config.MobsfProperties;
import io.mobsfmcp.config.ScanIndexProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** The app index: paging, caching and grouping over MobSF's recent-scans list. */
class ScanIndexTest {

	WireMockServer mobsf;

	@BeforeEach
	void start() {
		mobsf = MobsfStub.start();
	}

	@AfterEach
	void stop() {
		mobsf.stop();
	}

	private ScanIndex index(int pageSize, int maxScans, Duration ttl) {
		MobsfProperties properties = new MobsfProperties(mobsf.baseUrl(), MobsfStub.API_KEY, Path.of("/data/apks"),
				Path.of("/data/reports"), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30), 500);
		return new ScanIndex(new MobsfClient(properties, RestClient.builder()),
				new ScanIndexProperties(ttl, maxScans, pageSize));
	}

	private static String row(String hash, String file, String app, String pkg, String version) {
		return MobsfStub.scanRow(hash, file, app, pkg, version, "apk", "2026-09-2" + hash.charAt(0) + "T10:00:00Z");
	}

	private static String hash(int n) {
		return String.valueOf(n).repeat(32).substring(0, 32);
	}

	@Test
	void walksEveryPage() {
		List<String> rows = new ArrayList<>();
		for (int i = 1; i <= 7; i++) {
			rows.add(row(hash(i), "app" + i + ".apk", "App " + i, "com.example.app" + i, "1." + i));
		}
		MobsfStub.stubScanPages(mobsf, rows, 3);

		ScanIndex.Snapshot snapshot = index(3, 2000, Duration.ofSeconds(60)).get();

		assertThat(snapshot.scans()).hasSize(7);
		assertThat(snapshot.totalReported()).isEqualTo(7);
		assertThat(snapshot.truncated()).isFalse();
		mobsf.verify(getRequestedFor(urlPathEqualTo("/api/v1/scans")).withQueryParam("page", equalTo("3")));
	}

	@Test
	void doesNotDoubleCountWhenARowShiftsBetweenPages() {
		// a scan created mid-walk pushes every row down one page: page 2 then repeats a row
		// from page 1. Keying by hash keeps one copy instead of a duplicate plus a gap.
		String shared = row(hash(2), "b.apk", "B", "com.example.b", "1.0");
		mobsf.stubFor(get(urlPathEqualTo("/api/v1/scans")).withQueryParam("page", equalTo("1"))
			.willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("{\"content\": ["
					+ row(hash(1), "a.apk", "A", "com.example.a", "1.0") + ", " + shared
					+ "], \"count\": 4, \"num_pages\": 2}")));
		mobsf.stubFor(get(urlPathEqualTo("/api/v1/scans")).withQueryParam("page", equalTo("2"))
			.willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("{\"content\": [" + shared + ", "
					+ row(hash(3), "c.apk", "C", "com.example.c", "1.0") + "], \"count\": 4, \"num_pages\": 2}")));

		ScanIndex.Snapshot snapshot = index(2, 2000, Duration.ofSeconds(60)).get();

		assertThat(snapshot.scans()).extracting(ScanSummary::hash).containsExactly(hash(1), hash(2), hash(3))
			.doesNotHaveDuplicates();
	}

	@Test
	void capStopsTheWalkAndMarksTheIndexTruncated() {
		List<String> rows = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			rows.add(row(hash(i), "app" + i + ".apk", "App " + i, "com.example.app" + i, "1.0"));
		}
		MobsfStub.stubScanPages(mobsf, rows, 2);

		ScanIndex.Snapshot snapshot = index(2, 4, Duration.ofSeconds(60)).get();

		assertThat(snapshot.scans()).hasSize(4);
		assertThat(snapshot.truncated()).isTrue();
		assertThat(snapshot.totalReported()).isEqualTo(6);
	}

	@Test
	void aFailedPageReturnsWhatWasReadSoFar() {
		mobsf.stubFor(get(urlPathEqualTo("/api/v1/scans")).withQueryParam("page", equalTo("1"))
			.willReturn(com.github.tomakehurst.wiremock.client.WireMock
				.okJson("{\"content\": [" + row(hash(1), "a.apk", "A", "com.example.a", "1.0")
						+ "], \"count\": 4, \"num_pages\": 4}")));
		mobsf.stubFor(get(urlPathEqualTo("/api/v1/scans")).withQueryParam("page", equalTo("2"))
			.willReturn(aResponse().withStatus(500).withBody("boom")));

		ScanIndex.Snapshot snapshot = index(1, 2000, Duration.ofSeconds(60)).get();

		assertThat(snapshot.scans()).hasSize(1);
		assertThat(snapshot.truncated()).isTrue();
	}

	@Test
	void cachesUntilInvalidatedOrRefreshed() {
		MobsfStub.stubScanPages(mobsf, List.of(row(hash(1), "a.apk", "A", "com.example.a", "1.0")), 10);
		ScanIndex index = index(10, 2000, Duration.ofMinutes(5));

		index.get();
		index.get();
		mobsf.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/scans")).withQueryParam("page", equalTo("1")));

		index.get(true);
		mobsf.verify(2, getRequestedFor(urlPathEqualTo("/api/v1/scans")).withQueryParam("page", equalTo("1")));

		index.invalidate();
		index.get();
		mobsf.verify(3, getRequestedFor(urlPathEqualTo("/api/v1/scans")).withQueryParam("page", equalTo("1")));
	}

	@Test
	void expiredCacheIsRebuilt() {
		MobsfStub.stubScanPages(mobsf, List.of(row(hash(1), "a.apk", "A", "com.example.a", "1.0")), 10);
		ScanIndex index = index(10, 2000, Duration.ZERO);

		index.get();
		index.get();

		mobsf.verify(2, getRequestedFor(urlPathEqualTo("/api/v1/scans")).withQueryParam("page", equalTo("1")));
	}

	@Test
	void groupsVersionsOfOneAppNewestFirst() {
		MobsfStub.stubScanPages(mobsf,
				List.of(row(hash(3), "app-2.0.apk", "Demo", "com.example.demo", "2.0"),
						row(hash(2), "app-1.1.apk", "Demo", "com.example.demo", "1.1"),
						row(hash(1), "app-1.0.apk", "Demo", "com.example.demo", "1.0")),
				10);

		ScanIndex.Snapshot snapshot = index(10, 2000, Duration.ofSeconds(60)).get();

		assertThat(snapshot.groups()).hasSize(1);
		AppGroup group = snapshot.groups().get("com.example.demo");
		assertThat(group.scanCount()).isEqualTo(3);
		assertThat(group.versions()).extracting(ScanSummary::version).containsExactly("2.0", "1.1", "1.0");
		assertThat(group.newest().hash()).isEqualTo(hash(3));
		assertThat(group.version("1.1")).get().extracting(ScanSummary::hash).isEqualTo(hash(2));
	}

	@Test
	void appsSharingAFileNameStayApartAndBlankFieldsNeverBecomeAKey() {
		// the live MobSF has two different scans both called vulndemo-src.zip; one has no
		// package name at all, which must not collapse into a single blank-keyed group
		MobsfStub.stubScanPages(mobsf,
				List.of(MobsfStub.scanRow(hash(1), "vulndemo-src.zip", null, "com.example.vuln", null, "zip", "t1"),
						MobsfStub.scanRow(hash(2), "vulndemo-src.zip", null, null, null, "zip", "t2"),
						MobsfStub.scanRow(hash(3), "other.zip", null, null, null, "zip", "t3")),
				10);

		ScanIndex.Snapshot snapshot = index(10, 2000, Duration.ofSeconds(60)).get();

		assertThat(snapshot.groups()).hasSize(3);
		assertThat(snapshot.groups()).containsKeys("com.example.vuln", "vulndemo-src.zip", "other.zip");
		assertThat(snapshot.groups().keySet()).doesNotContain("");
		assertThat(snapshot.scans()).allSatisfy(scan -> {
			assertThat(scan.appName()).isNull();     // "" from MobSF becomes null, not ""
			assertThat(scan.version()).isNull();
		});
	}

	@Test
	void matchesOnAnyNameAndOnHash() {
		MobsfStub.stubScanPages(mobsf,
				List.of(row(hash(1), "demo-app.apk", "Demo App", "com.example.demo", "1.0"),
						row(hash(2), "other.apk", "Other", "com.other.thing", "1.0")),
				10);

		ScanIndex.Snapshot snapshot = index(10, 2000, Duration.ofSeconds(60)).get();

		assertThat(snapshot.matching("demo")).hasSize(1);
		assertThat(snapshot.matching("DEMO")).hasSize(1);          // case-insensitive
		assertThat(snapshot.matching("com.")).hasSize(2);
		assertThat(snapshot.matching("nothing-like-this")).isEmpty();
		assertThat(snapshot.byHash(hash(2))).get().extracting(AppGroup::appKey).isEqualTo("com.other.thing");
	}

	@Test
	void recognisesHashesButNotOtherText() {
		assertThat(ScanIndex.isHash("82ab8b2193b3cfb1c737e3a786be363a")).isTrue();
		assertThat(ScanIndex.isHash("82AB8B2193B3CFB1C737E3A786BE363A")).isTrue();
		assertThat(ScanIndex.isHash("com.example.app")).isFalse();
		assertThat(ScanIndex.isHash("82ab8b")).isFalse();
		assertThat(ScanIndex.isHash(null)).isFalse();
	}

}
