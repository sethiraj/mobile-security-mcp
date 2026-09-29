// SPDX-License-Identifier: MIT
package io.mobsfmcp.scans;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.client.MobsfException;

import org.springframework.stereotype.Service;

/**
 * Chooses which scans may be deleted from MobSF, and deletes them.
 * <p>
 * MobSF keeps each scan's uploaded app and its decompiled sources, so a busy instance
 * grows steadily. Nothing here runs on its own: a tool call decides, and a dry run is the
 * default.
 */
@Service
public class RetentionService {

	/** MobSF writes scan-log timestamps in this shape; the scan list uses ISO-8601. */
	private static final DateTimeFormatter MOBSF_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final MobsfClient client;

	private final ScanIndex index;

	public RetentionService(MobsfClient client, ScanIndex index) {
		this.client = client;
		this.index = index;
	}

	/**
	 * @param reason which rule selected this scan, so a dry run explains itself
	 */
	public record Candidate(String hash, String appKey, String version, String fileName, String timestamp,
			String reason) {
	}

	public record Plan(int scannedApps, int totalScans, int keepPerApp, int maxAgeDays, List<Candidate> candidates,
			int keptCount) {
	}

	public record Outcome(List<Candidate> deleted, List<Candidate> skipped, Map<String, String> failed) {
	}

	/**
	 * Works out what may go. Candidates are the scans beyond {@code keepPerApp} in each
	 * app, and — when {@code maxAgeDays} is set — only those also older than that.
	 * <p>
	 * The newest scan of every distinct app identity is never a candidate. Grouping keys on
	 * package name, then app name, then file name, so two unrelated apps can share a group;
	 * a floor per group alone would let {@code keepPerApp} delete the only scan of one.
	 */
	public Plan plan(int keepPerApp, int maxAgeDays, boolean refresh) {
		ScanIndex.Snapshot snapshot = index.get(refresh);
		Instant cutoff = maxAgeDays > 0 ? Instant.now().minus(Duration.ofDays(maxAgeDays)) : null;

		List<Candidate> candidates = new ArrayList<>();
		int kept = 0;
		for (AppGroup group : snapshot.groups().values()) {
			Set<String> newestPerIdentity = newestPerIdentity(group);
			for (int position = 0; position < group.versions().size(); position++) {
				ScanSummary scan = group.versions().get(position);
				boolean protectedScan = newestPerIdentity.contains(scan.hash());
				boolean beyondKeep = position >= keepPerApp;
				if (protectedScan || !beyondKeep) {
					kept++;
					continue;
				}
				if (cutoff != null && !isOlderThan(scan, cutoff)) {
					kept++;   // beyond the count limit but still inside the age limit
					continue;
				}
				String reason = cutoff != null
						? "beyond the newest " + keepPerApp + " for " + group.appKey() + " and older than "
								+ maxAgeDays + " days"
						: "beyond the newest " + keepPerApp + " for " + group.appKey();
				candidates.add(new Candidate(scan.hash(), group.appKey(), scan.version(), scan.fileName(),
						scan.timestamp(), reason));
			}
		}
		return new Plan(snapshot.groups().size(), snapshot.scans().size(), keepPerApp, maxAgeDays, List.copyOf(candidates),
				kept);
	}

	/**
	 * Deletes the hashes a dry run produced, after re-checking them against a fresh plan:
	 * anything no longer selected (or already gone) is skipped rather than deleted.
	 */
	public Outcome delete(List<String> hashes, int keepPerApp, int maxAgeDays) {
		Map<String, Candidate> selectable = new LinkedHashMap<>();
		plan(keepPerApp, maxAgeDays, true).candidates().forEach(candidate -> selectable.put(candidate.hash(), candidate));

		List<Candidate> deleted = new ArrayList<>();
		List<Candidate> skipped = new ArrayList<>();
		Map<String, String> failed = new LinkedHashMap<>();

		for (String hash : hashes) {
			Candidate candidate = selectable.get(hash);
			if (candidate == null) {
				skipped.add(new Candidate(hash, null, null, null, null,
						"no longer selected by the retention rules, or already deleted"));
				continue;
			}
			try {
				client.deleteScan(hash);
				deleted.add(candidate);
			}
			catch (MobsfException ex) {
				// one bad hash must not abandon the rest of the run
				failed.put(hash, ex.getMessage());
			}
		}
		if (!deleted.isEmpty()) {
			index.invalidate();
		}
		return new Outcome(List.copyOf(deleted), List.copyOf(skipped), Map.copyOf(failed));
	}

	/** Hashes that are the most recent scan of their exact app identity. */
	private static Set<String> newestPerIdentity(AppGroup group) {
		Map<String, String> newest = new HashMap<>();
		for (ScanSummary scan : group.versions()) {
			newest.putIfAbsent(scan.identityKey(), scan.hash());   // versions are newest first
		}
		return new HashSet<>(newest.values());
	}

	/**
	 * A timestamp that cannot be parsed is treated as "not old enough", so a format change
	 * in MobSF can never cause a deletion.
	 */
	static boolean isOlderThan(ScanSummary scan, Instant cutoff) {
		Instant when = parse(scan.timestamp());
		return when != null && when.isBefore(cutoff);
	}

	static Instant parse(String timestamp) {
		if (timestamp == null || timestamp.isBlank()) {
			return null;
		}
		String value = timestamp.trim();
		try {
			return OffsetDateTime.parse(value).toInstant();
		}
		catch (RuntimeException ignored) {
			// not ISO-8601 with an offset
		}
		try {
			return Instant.parse(value);
		}
		catch (RuntimeException ignored) {
			// not an instant either
		}
		try {
			return LocalDateTime.parse(value, MOBSF_TIMESTAMP).toInstant(ZoneOffset.UTC);
		}
		catch (RuntimeException ignored) {
			return null;
		}
	}

}
