// SPDX-License-Identifier: MIT
package io.mobsfmcp.scans;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.client.MobsfException;
import io.mobsfmcp.config.ScanIndexProperties;
import tools.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/**
 * An app-centric view of MobSF's scans, built by walking {@code /api/v1/scans}.
 * <p>
 * MobSF cannot answer "show me every scan of this app": its search matches the first row
 * only and then returns that scan's whole report. So the list is paged in here, grouped by
 * app, and cached briefly.
 */
@Component
public class ScanIndex {

	/** Hex MD5, the form MobSF uses for scan hashes. */
	private static final String MD5_PATTERN = "[a-fA-F0-9]{32}";

	private final MobsfClient client;

	private final ScanIndexProperties properties;

	private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

	/** Serializes rebuilds: without it, concurrent tool calls each walk every page. */
	private final ReentrantLock rebuildLock = new ReentrantLock();

	public ScanIndex(MobsfClient client, ScanIndexProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	/**
	 * @param scans every scan MobSF returned, newest first
	 * @param groups scans grouped by app, insertion-ordered newest app first
	 * @param truncated true when the cap was hit or a page failed, so the view is partial
	 * @param totalReported MobSF's own scan count, which may exceed {@code scans.size()}
	 */
	public record Snapshot(List<ScanSummary> scans, Map<String, AppGroup> groups, Instant builtAt, boolean truncated,
			int totalReported) {

		public List<AppGroup> matching(String query) {
			String trimmed = query.trim();
			return groups.values()
				.stream()
				.filter(group -> group.versions().stream().anyMatch(scan -> scan.matches(trimmed)))
				.toList();
		}

		public Optional<AppGroup> byHash(String hash) {
			return groups.values()
				.stream()
				.filter(group -> group.versions().stream().anyMatch(scan -> hash.equalsIgnoreCase(scan.hash())))
				.findFirst();
		}
	}

	/** The current index, rebuilt when {@code refresh} is set or the cached copy has expired. */
	public Snapshot get(boolean refresh) {
		Snapshot current = snapshot.get();
		if (!refresh && current != null && !isExpired(current)) {
			return current;
		}
		rebuildLock.lock();
		try {
			// another thread may have rebuilt while this one waited
			Snapshot afterLock = snapshot.get();
			if (!refresh && afterLock != null && !isExpired(afterLock)) {
				return afterLock;
			}
			if (refresh && afterLock != null && afterLock != current && !isExpired(afterLock)) {
				return afterLock;
			}
			Snapshot rebuilt = build();
			snapshot.set(rebuilt);
			return rebuilt;
		}
		finally {
			rebuildLock.unlock();
		}
	}

	public Snapshot get() {
		return get(false);
	}

	/** Drops the cached index. Called whenever this server changes MobSF's scan list. */
	public void invalidate() {
		snapshot.set(null);
	}

	public static boolean isHash(String value) {
		return value != null && value.trim().matches(MD5_PATTERN);
	}

	private boolean isExpired(Snapshot current) {
		Duration ttl = properties.cacheTtl();
		return ttl.isZero() || ttl.isNegative() || current.builtAt().plus(ttl).isBefore(Instant.now());
	}

	private Snapshot build() {
		// keyed by hash: MobSF returns newest first, so a scan created mid-walk shifts every
		// row down a page and would otherwise appear twice while another is missed entirely
		Map<String, ScanSummary> byHash = new LinkedHashMap<>();
		boolean truncated = false;
		int totalReported = 0;
		int pageSize = Math.max(1, properties.pageSize());
		int maxPages = Math.max(1, properties.maxScans() / pageSize + 1);

		for (int page = 1; page <= maxPages; page++) {
			JsonNode body;
			try {
				body = client.recentScans(page, pageSize);
			}
			catch (MobsfException ex) {
				if (page == 1) {
					throw ex;   // nothing to fall back on
				}
				truncated = true;
				break;
			}
			if (page == 1) {
				totalReported = body.path("count").asInt(0);
			}
			int before = byHash.size();
			int rows = 0;
			for (JsonNode item : body.path("content")) {
				rows++;
				ScanSummary scan = ScanSummary.from(item);
				if (scan.hash() != null) {
					byHash.putIfAbsent(scan.hash(), scan);
				}
			}
			int numPages = body.path("num_pages").asInt(1);
			if (byHash.size() >= properties.maxScans()) {
				truncated = byHash.size() < totalReported;
				break;
			}
			// stop on a short page, a page that added nothing new, or the last page
			if (rows < pageSize || byHash.size() == before || page >= numPages) {
				break;
			}
		}

		List<ScanSummary> scans = List.copyOf(byHash.values());
		return new Snapshot(scans, group(scans), Instant.now(), truncated || scans.size() < totalReported,
				Math.max(totalReported, scans.size()));
	}

	private static Map<String, AppGroup> group(List<ScanSummary> scans) {
		Map<String, List<ScanSummary>> byApp = new LinkedHashMap<>();
		for (ScanSummary scan : scans) {
			byApp.computeIfAbsent(scan.appKey(), key -> new ArrayList<>()).add(scan);
		}
		Map<String, AppGroup> groups = new LinkedHashMap<>();
		byApp.forEach((key, versions) -> groups.put(key, AppGroup.of(key, versions)));
		return Map.copyOf(groups);
	}

}
