// SPDX-License-Identifier: MIT
package io.mobsfmcp.scans;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Timestamp handling, which decides whether a scan is old enough to delete. MobSF uses
 * ISO-8601 in the scan list and {@code yyyy-MM-dd HH:mm:ss} in scan logs.
 */
class RetentionServiceTest {

	@Test
	void parsesTheFormatsMobsfActuallyUses() {
		assertThat(RetentionService.parse("2026-09-24T07:08:26.216Z")).isNotNull();
		assertThat(RetentionService.parse("2026-09-24T07:08:26+02:00")).isNotNull();
		assertThat(RetentionService.parse("2026-09-24 07:08:26")).isNotNull();
	}

	@Test
	void anythingUnparseableIsNull() {
		assertThat(RetentionService.parse("not-a-timestamp")).isNull();
		assertThat(RetentionService.parse("")).isNull();
		assertThat(RetentionService.parse("  ")).isNull();
		assertThat(RetentionService.parse(null)).isNull();
	}

	@Test
	void aScanWithAnUnreadableTimestampIsNeverOldEnoughToDelete() {
		// a format change in MobSF must never cause a deletion
		Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
		ScanSummary unreadable = new ScanSummary("h", "a.apk", "A", "com.a", "1.0", "apk", "static_analyzer",
				"whenever");
		ScanSummary missing = new ScanSummary("h", "a.apk", "A", "com.a", "1.0", "apk", "static_analyzer", null);
		ScanSummary old = new ScanSummary("h", "a.apk", "A", "com.a", "1.0", "apk", "static_analyzer",
				Instant.now().minus(10, ChronoUnit.DAYS).toString());

		assertThat(RetentionService.isOlderThan(unreadable, cutoff)).isFalse();
		assertThat(RetentionService.isOlderThan(missing, cutoff)).isFalse();
		assertThat(RetentionService.isOlderThan(old, cutoff)).isTrue();
	}

}
