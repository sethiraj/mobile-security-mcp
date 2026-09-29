// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.client.MobsfException;
import io.mobsfmcp.scans.AppGroup;
import io.mobsfmcp.scans.ScanIndex;
import io.mobsfmcp.scans.ScanSummary;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Finding an app among MobSF's scans and working with its versions.
 * <p>
 * MobSF's own search cannot do this: it matches one row and returns that scan's entire
 * report, so these tools work from {@link ScanIndex} instead.
 */
@Component
public class AppScanTools {

	static final int MAX_SCORED = 10;

	private final ScanIndex index;

	private final MobsfClient client;

	private final StaticAnalysisTools staticTools;

	public AppScanTools(ScanIndex index, MobsfClient client, StaticAnalysisTools staticTools) {
		this.index = index;
		this.client = client;
		this.staticTools = staticTools;
	}

	public record AppMatch(String appKey, String appName, String packageName, String scanType, int scanCount,
			List<ScanSummary> versions) {
	}

	public record AppMatches(String query, int matchCount, boolean ambiguous, boolean indexTruncated,
			List<AppMatch> apps, String nextStep) {
	}

	public record ScoredScan(String hash, String version, String fileName, String scanType, String timestamp,
			Integer securityScore, String scoreError) {
	}

	public record AppScans(String appKey, String appName, String packageName, int scanCount, List<ScoredScan> scans,
			String nextStep) {
	}

	/**
	 * @param fromVersion, toVersion what the tool actually compared, so the agent can see
	 * which scans were chosen when it did not name them
	 */
	public record VersionComparison(String appKey, String fromVersion, String fromHash, String toVersion,
			String toHash, String summary, StaticAnalysisTools.ScanComparison findings) {
	}

	@McpTool(name = "find_app",
			description = "Find apps among MobSF's scans by name, package name or hash, with every scanned version of "
					+ "each. Use this instead of guessing hashes: it shows which versions exist and warns when more "
					+ "than one app matches. Returns matchCount 0 (not an error) when nothing matches.",
			annotations = @McpTool.McpAnnotations(title = "Find app and its versions", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public AppMatches findApp(
			@McpToolParam(description = "App name, package name, file name or MD5 hash; substring match") String query,
			@McpToolParam(description = "Maximum apps to return (default 10)", required = false) Integer limit,
			@McpToolParam(description = "Rebuild the index before searching (default false)", required = false) Boolean refresh) {
		String trimmed = require(query, "'query' is required: an app name, package name, file name or hash.");
		ScanIndex.Snapshot snapshot = index.get(Boolean.TRUE.equals(refresh));

		List<AppGroup> matches;
		if (ScanIndex.isHash(trimmed)) {
			Optional<AppGroup> byHash = snapshot.byHash(trimmed);
			if (byHash.isEmpty()) {
				return hashNotIndexed(trimmed, snapshot);
			}
			matches = List.of(byHash.get());
		}
		else {
			matches = snapshot.matching(trimmed);
		}

		int max = limit == null || limit < 1 ? 10 : limit;
		List<AppMatch> apps = matches.stream().limit(max).map(AppScanTools::toMatch).toList();

		if (apps.isEmpty()) {
			return new AppMatches(trimmed, 0, false, snapshot.truncated(), List.of(),
					"No scan matches '" + trimmed + "'. Call list_scans to see what has been scanned, or upload_app "
							+ "and scan_app to scan it now."
							+ (snapshot.truncated() ? " Note: the index is truncated, so older scans may be missing."
									: ""));
		}
		boolean ambiguous = matches.size() > 1;
		String nextStep = ambiguous
				? matches.size() + " apps match '" + trimmed + "'. Pick one by packageName (apps can share a file "
						+ "name) and pass a version's hash to the report tools."
				: "Pass a version's hash to get_scorecard or get_report, or use compare_app_versions.";
		return new AppMatches(trimmed, matches.size(), ambiguous, snapshot.truncated(), apps, nextStep);
	}

	@McpTool(name = "list_app_scans",
			description = "Every scan MobSF holds for one app, newest first - useful when an app has been scanned "
					+ "across several releases. Set includeScores to add each scan's security score (slower: one call "
					+ "per scan, newest 10 only).",
			annotations = @McpTool.McpAnnotations(title = "List an app's scans", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public AppScans listAppScans(
			@McpToolParam(description = "App name, package name, file name or hash of any of its scans") String app,
			@McpToolParam(description = "Add the security score of each scan (default false)", required = false) Boolean includeScores,
			@McpToolParam(description = "Rebuild the index first (default false)", required = false) Boolean refresh) {
		AppGroup group = resolve(app, Boolean.TRUE.equals(refresh));
		boolean scores = Boolean.TRUE.equals(includeScores);

		List<ScoredScan> scans = new ArrayList<>();
		for (int i = 0; i < group.versions().size(); i++) {
			ScanSummary scan = group.versions().get(i);
			Integer score = null;
			String scoreError = null;
			if (scores && i < MAX_SCORED) {
				try {
					score = staticTools.getScorecard(scan.hash()).securityScore();
				}
				catch (MobsfException ex) {
					// a scan that was uploaded but never analysed has no scorecard yet
					scoreError = ex.getMessage();
				}
			}
			scans.add(new ScoredScan(scan.hash(), scan.version(), scan.fileName(), scan.scanType(), scan.timestamp(),
					score, scoreError));
		}
		String nextStep = group.scanCount() > 1
				? "Use compare_app_versions to see what changed between two of these scans."
				: "Only one scan exists for this app; scan a newer build to compare releases.";
		return new AppScans(group.appKey(), group.appName(), group.packageName(), group.scanCount(), scans, nextStep);
	}

	@McpTool(name = "compare_app_versions",
			description = "Compare two scanned versions of the same app: the security score difference and which high "
					+ "severity findings appear in only one of them. Defaults to the two most recent scans, so "
					+ "compare_app_versions with just the app name answers 'did this release get better or worse?'.",
			annotations = @McpTool.McpAnnotations(title = "Compare two versions of an app", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public VersionComparison compareAppVersions(
			@McpToolParam(description = "App name, package name, file name or hash of any of its scans") String app,
			@McpToolParam(description = "Older version to compare, e.g. 1.0 (default: the second most recent scan)", required = false) String fromVersion,
			@McpToolParam(description = "Newer version to compare, e.g. 2.0 (default: the most recent scan)", required = false) String toVersion,
			@McpToolParam(description = "Rebuild the index first (default false)", required = false) Boolean refresh) {
		AppGroup group = resolve(app, Boolean.TRUE.equals(refresh));
		if (group.scanCount() < 2 && (fromVersion == null || toVersion == null)) {
			throw new IllegalArgumentException("Only one scan exists for " + group.appKey()
					+ " (version " + describe(group.newest()) + "). Scan another build of the app first, then compare.");
		}

		ScanSummary to = pick(group, toVersion, 0);
		ScanSummary from = pick(group, fromVersion, 1);
		if (to.hash().equals(from.hash())) {
			throw new IllegalArgumentException(
					"'fromVersion' and 'toVersion' resolve to the same scan (" + to.hash() + "). Pick two different versions.");
		}
		if (from.scanType() != null && !from.scanType().equalsIgnoreCase(to.scanType())) {
			throw new IllegalArgumentException("Refusing to compare a " + from.scanType() + " scan with a "
					+ to.scanType() + " scan: their findings are not comparable. Compare scans of the same type.");
		}

		StaticAnalysisTools.ScanComparison comparison = staticTools.compareScans(from.hash(), to.hash());
		String summary = comparison.scoreDelta() == null ? "Scores are not available for both scans."
				: comparison.scoreDelta() == 0 ? "The security score is unchanged."
						: comparison.scoreDelta() > 0
								? "The security score improved by " + comparison.scoreDelta() + " points."
								: "The security score dropped by " + Math.abs(comparison.scoreDelta()) + " points.";
		return new VersionComparison(group.appKey(), describe(from), from.hash(), describe(to), to.hash(), summary,
				comparison);
	}

	@McpTool(name = "search_scan",
			description = "Search MobSF's scans by hash, file name, app name or package name. Returns every match, "
					+ "newest first, grouped by app. Nothing matching is not an error.",
			annotations = @McpTool.McpAnnotations(title = "Search scans", readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	public AppMatches searchScan(@McpToolParam(description = "Hash, file name, app name or package name") String query,
			@McpToolParam(description = "Rebuild the index first (default false)", required = false) Boolean refresh) {
		return findApp(query, 25, refresh);
	}

	/** Resolves an app reference (name, package, file name or hash) to exactly one group. */
	AppGroup resolve(String app, boolean refresh) {
		String trimmed = require(app, "'app' is required: an app name, package name, file name or scan hash.");
		ScanIndex.Snapshot snapshot = index.get(refresh);

		if (ScanIndex.isHash(trimmed)) {
			return snapshot.byHash(trimmed)
				.orElseThrow(() -> new IllegalArgumentException("No scan with hash " + trimmed
						+ " is in the index. Call list_scans, or find_app with the app's name."));
		}
		List<AppGroup> matches = snapshot.matching(trimmed);
		if (matches.isEmpty()) {
			throw new IllegalArgumentException("No app matches '" + trimmed
					+ "'. Call find_app or list_scans to see what has been scanned.");
		}
		if (matches.size() > 1) {
			String names = matches.stream()
				.limit(5)
				.map(group -> group.appKey() + " (" + group.scanCount() + " scans)")
				.reduce((a, b) -> a + ", " + b)
				.orElse("");
			throw new IllegalArgumentException(matches.size() + " apps match '" + trimmed + "': " + names
					+ ". Use a package name, or pass the hash of one of its scans.");
		}
		return matches.get(0);
	}

	private AppMatches hashNotIndexed(String hash, ScanIndex.Snapshot snapshot) {
		// the index may be capped or stale, so ask MobSF directly before giving up
		try {
			client.search(hash);
			return new AppMatches(hash, 0, false, snapshot.truncated(), List.of(),
					"MobSF knows this hash but it is not in the index"
							+ (snapshot.truncated() ? " (the index is truncated; raise MCP_SCAN_INDEX_MAX_SCANS)"
									: " (the index may be stale; retry with refresh=true)")
							+ ". The report tools accept the hash directly.");
		}
		catch (MobsfException ex) {
			return new AppMatches(hash, 0, false, snapshot.truncated(), List.of(),
					"No scan with hash " + hash + " exists in MobSF. Upload and scan the app first.");
		}
	}

	/** The named version, or the scan at {@code defaultIndex} counting back from the newest. */
	private static ScanSummary pick(AppGroup group, String version, int defaultIndex) {
		if (version == null || version.isBlank()) {
			return group.versions().get(Math.min(defaultIndex, group.versions().size() - 1));
		}
		return group.version(version.trim())
			.orElseThrow(() -> new IllegalArgumentException("No scan of " + group.appKey() + " has version '" + version
					+ "'. Known versions: " + known(group) + ". Call list_app_scans to see them all."));
	}

	private static String known(AppGroup group) {
		return group.versions()
			.stream()
			.map(AppScanTools::describe)
			.distinct()
			.limit(10)
			.reduce((a, b) -> a + ", " + b)
			.orElse("none");
	}

	/** Version name when MobSF has one; otherwise something an agent can still refer to. */
	private static String describe(ScanSummary scan) {
		if (scan.version() != null) {
			return scan.version();
		}
		return scan.fileName() != null ? scan.fileName() : scan.hash();
	}

	private static AppMatch toMatch(AppGroup group) {
		return new AppMatch(group.appKey(), group.appName(), group.packageName(), group.scanType(), group.scanCount(),
				group.versions());
	}

	private static String require(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
		return value.trim();
	}

}
