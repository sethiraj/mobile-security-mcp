// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.mobsfmcp.MobsfStub;
import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.config.MobsfProperties;
import io.mobsfmcp.config.RetentionProperties;
import io.mobsfmcp.config.ScanIndexProperties;
import io.mobsfmcp.scans.RetentionService;
import io.mobsfmcp.scans.ScanIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** prune_scans: the rules, and the rails that stop it deleting the wrong thing. */
class RetentionToolsTest {

	WireMockServer mobsf;

	ScanIndex index;

	@BeforeEach
	void start() {
		mobsf = MobsfStub.start();
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/delete_scan")).willReturn(okJson("{\"deleted\": \"scan deleted\"}")));
	}

	@AfterEach
	void stop() {
		mobsf.stop();
	}

	private static String hash(int n) {
		return String.format("%032d", n);
	}

	private static String daysAgo(int days) {
		return Instant.now().minus(days, ChronoUnit.DAYS).toString();
	}

	/** Four scans of one app plus one of another, newest first. */
	private void stubStandardHistory() {
		MobsfStub.stubScanPages(mobsf,
				List.of(MobsfStub.scanRow(hash(4), "demo-4.apk", "Demo", "com.example.demo", "4.0", "apk", daysAgo(1)),
						MobsfStub.scanRow(hash(3), "demo-3.apk", "Demo", "com.example.demo", "3.0", "apk", daysAgo(40)),
						MobsfStub.scanRow(hash(2), "demo-2.apk", "Demo", "com.example.demo", "2.0", "apk", daysAgo(80)),
						MobsfStub.scanRow(hash(1), "demo-1.apk", "Demo", "com.example.demo", "1.0", "apk", daysAgo(120)),
						MobsfStub.scanRow(hash(9), "other.apk", "Other", "com.other.app", "1.0", "apk", daysAgo(200))),
				10);
	}

	private RetentionTools tools(RetentionProperties properties) {
		MobsfProperties mobsfProperties = new MobsfProperties(mobsf.baseUrl(), MobsfStub.API_KEY, Path.of("/data/apks"),
				Path.of("/data/reports"), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30), 500);
		MobsfClient client = new MobsfClient(mobsfProperties, RestClient.builder());
		index = new ScanIndex(client, new ScanIndexProperties(Duration.ZERO, 2000, 100));
		return new RetentionTools(new RetentionService(client, index), properties);
	}

	private RetentionTools tools() {
		return tools(new RetentionProperties(true, 5, 0));
	}

	@Test
	void dryRunIsTheDefaultAndDeletesNothing() {
		stubStandardHistory();

		RetentionTools.PruneResult result = tools().pruneScans(null, 2, null, null, null);

		assertThat(result.dryRun()).isTrue();
		assertThat(result.selectedCount()).isEqualTo(2);   // versions 2.0 and 1.0
		assertThat(result.hashes()).containsExactly(hash(2), hash(1));
		assertThat(result.selected()).allSatisfy(c -> assertThat(c.reason()).contains("beyond the newest 2"));
		assertThat(result.nextStep()).contains("dryRun=false");
		mobsf.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/delete_scan")));
	}

	@Test
	void theNewestScanOfEveryAppIsNeverSelected() {
		stubStandardHistory();

		RetentionTools.PruneResult result = tools().pruneScans(null, 0, null, null, null);

		// keepPerApp=0 would delete everything, but each app's newest scan is protected
		assertThat(result.hashes()).contains(hash(3), hash(2), hash(1)).doesNotContain(hash(4), hash(9));
	}

	@Test
	void ageLimitNarrowsTheSelection() {
		stubStandardHistory();

		RetentionTools.PruneResult result = tools().pruneScans(null, 1, 100, null, null);

		// beyond the newest 1 AND older than 100 days: only version 1.0 qualifies
		assertThat(result.hashes()).containsExactly(hash(1));
		assertThat(result.selected().get(0).reason()).contains("older than 100 days");
	}

	@Test
	void unparseableTimestampsAreNeverTreatedAsOld() {
		MobsfStub.stubScanPages(mobsf,
				List.of(MobsfStub.scanRow(hash(2), "a-2.apk", "A", "com.a", "2.0", "apk", daysAgo(1)),
						MobsfStub.scanRow(hash(1), "a-1.apk", "A", "com.a", "1.0", "apk", "not-a-timestamp")),
				10);

		assertThat(tools().pruneScans(null, 1, 30, null, null).hashes()).isEmpty();
	}

	@Test
	void appsThatMerelyShareAGroupKeyEachKeepTheirNewestScan() {
		// two unrelated zips both called notes.zip: no package name, so both group by file
		// name. A per-group floor alone would delete the only scan of one of them.
		MobsfStub.stubScanPages(mobsf,
				List.of(MobsfStub.scanRow(hash(3), "notes.zip", "Notes A", null, null, "zip", daysAgo(1)),
						MobsfStub.scanRow(hash(2), "notes.zip", "Notes A", null, null, "zip", daysAgo(5)),
						MobsfStub.scanRow(hash(1), "notes.zip", "Notes B", null, null, "zip", daysAgo(9))),
				10);

		RetentionTools.PruneResult result = tools().pruneScans(null, 1, null, null, null);

		assertThat(result.hashes()).containsExactly(hash(2));   // only the older Notes A scan
	}

	@Test
	void aRealRunNeedsAnExplicitRuleAndTheHashesFromTheDryRun() {
		stubStandardHistory();

		assertThatThrownBy(() -> tools().pruneScans(false, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must state its rule");

		assertThatThrownBy(() -> tools().pruneScans(false, 2, null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'hashes' is required");

		mobsf.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/delete_scan")));
	}

	@Test
	void aRealRunDeletesOnlyWhatIsStillSelected() {
		stubStandardHistory();
		RetentionTools tools = tools();

		RetentionTools.PruneResult result = tools.pruneScans(false, 2, null,
				List.of(hash(2), hash(1), hash(4), "ffffffffffffffffffffffffffffffff"), null);

		assertThat(result.deleted()).extracting(RetentionService.Candidate::hash).containsExactly(hash(2), hash(1));
		// the newest scan and an unknown hash are skipped rather than deleted
		assertThat(result.skipped()).extracting(RetentionService.Candidate::hash).contains(hash(4),
				"ffffffffffffffffffffffffffffffff");
		assertThat(result.failed()).isEmpty();
		mobsf.verify(2, postRequestedFor(urlPathEqualTo("/api/v1/delete_scan")));
	}

	@Test
	void oneFailedDeletionDoesNotAbandonTheRest() {
		stubStandardHistory();
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/delete_scan")).withRequestBody(containing("hash=" + hash(2)))
			.willReturn(aResponse().withStatus(500).withBody("{\"error\": \"could not delete\"}")));

		RetentionTools.PruneResult result = tools().pruneScans(false, 2, null, List.of(hash(2), hash(1)), null);

		assertThat(result.failed()).containsOnlyKeys(hash(2));
		assertThat(result.deleted()).extracting(RetentionService.Candidate::hash).containsExactly(hash(1));
		assertThat(result.nextStep()).contains("1 deletions failed");
	}

	@Test
	void disabledRetentionRefusesButStaysVisible() {
		stubStandardHistory();

		RetentionTools.PruneResult result = tools(new RetentionProperties(false, 5, 0))
			.pruneScans(false, 1, null, List.of(hash(1)), null);

		assertThat(result.enabled()).isFalse();
		assertThat(result.deleted()).isEmpty();
		assertThat(result.nextStep()).contains("MCP_RETENTION_ENABLED=true");
		mobsf.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/delete_scan")));
	}

	@Test
	void exportedReportsAreKeptAndSaidSo() {
		stubStandardHistory();

		assertThat(tools().pruneScans(null, 2, null, null, null).reportsNote())
			.contains("NOT deleted")
			.contains("audit trail");
	}

	@Test
	void negativeRulesAreRejected() {
		stubStandardHistory();

		assertThatThrownBy(() -> tools().pruneScans(null, -1, null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("cannot be negative");
	}

	@Test
	void deleteRequestsGoToMobsfNotToADeleteVerb() {
		stubStandardHistory();

		tools().pruneScans(false, 3, null, List.of(hash(1)), null);

		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/delete_scan")).withRequestBody(containing(hash(1))));
		mobsf.verify(0, deleteRequestedFor(urlPathEqualTo("/api/v1/delete_scan")));
	}

}
