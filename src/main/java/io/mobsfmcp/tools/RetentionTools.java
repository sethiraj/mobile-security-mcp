// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.util.List;
import java.util.Map;

import io.mobsfmcp.config.RetentionProperties;
import io.mobsfmcp.scans.RetentionService;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Deleting old scans from MobSF, which is where the disk actually goes: MobSF keeps each
 * uploaded app and its decompiled sources.
 * <p>
 * Deliberately awkward to fire by accident: a dry run is the default, a real run must name
 * a rule, the newest scan of every app is never touched, and the hashes to delete come
 * from the dry run rather than being recomputed blind.
 */
@Component
public class RetentionTools {

	private final RetentionService retention;

	private final RetentionProperties properties;

	public RetentionTools(RetentionService retention, RetentionProperties properties) {
		this.retention = retention;
		this.properties = properties;
	}

	public record PruneResult(boolean dryRun, boolean enabled, int keepPerApp, int maxAgeDays, int appsScanned,
			int totalScans, int selectedCount, int keptCount, List<RetentionService.Candidate> selected,
			List<String> hashes, List<RetentionService.Candidate> deleted, List<RetentionService.Candidate> skipped,
			Map<String, String> failed, String reportsNote, String nextStep) {
	}

	@McpTool(name = "prune_scans",
			description = "Delete old scans from MobSF to reclaim disk (MobSF stores each uploaded app and its "
					+ "decompiled sources). Defaults to a DRY RUN that only lists what would go. To delete, set "
					+ "dryRun=false AND pass the hashes from the dry run. The newest scan of every app is never "
					+ "deleted, and exported reports on disk are kept.",
			annotations = @McpTool.McpAnnotations(title = "Prune old scans", readOnlyHint = false,
					destructiveHint = true, idempotentHint = false, openWorldHint = false))
	public PruneResult pruneScans(
			@McpToolParam(description = "false actually deletes; default true only reports what would be deleted", required = false) Boolean dryRun,
			@McpToolParam(description = "Scans to keep per app, newest first", required = false) Integer keepPerApp,
			@McpToolParam(description = "Only delete scans older than this many days (0 = no age limit)", required = false) Integer maxAgeDays,
			@McpToolParam(description = "Hashes to delete, taken from a previous dry run; required when dryRun=false", required = false) List<String> hashes,
			@McpToolParam(description = "Rebuild the scan index first (default false)", required = false) Boolean refresh) {
		boolean dry = dryRun == null || dryRun;

		if (!properties.enabled()) {
			return refusal("Pruning is disabled on this server. Set MCP_RETENTION_ENABLED=true to allow it.");
		}

		int keep = keepPerApp != null ? keepPerApp : properties.keepPerApp();
		int age = maxAgeDays != null ? maxAgeDays : properties.maxAgeDays();
		if (keep < 0 || age < 0) {
			throw new IllegalArgumentException("'keepPerApp' and 'maxAgeDays' cannot be negative.");
		}
		// a bare prune_scans(dryRun=false) must not quietly delete everything beyond the default
		if (!dry && keepPerApp == null && maxAgeDays == null) {
			throw new IllegalArgumentException("A real prune must state its rule: pass keepPerApp and/or maxAgeDays "
					+ "explicitly (the server defaults are keepPerApp=" + properties.keepPerApp() + ", maxAgeDays="
					+ properties.maxAgeDays() + "). Run a dry run first to see what they would delete.");
		}

		RetentionService.Plan plan = retention.plan(keep, age, Boolean.TRUE.equals(refresh));
		List<String> selectedHashes = plan.candidates().stream().map(RetentionService.Candidate::hash).toList();

		if (dry) {
			String nextStep = plan.candidates().isEmpty()
					? "Nothing matches these rules, so there is nothing to delete."
					: "To delete these " + plan.candidates().size() + " scans, call prune_scans again with "
							+ "dryRun=false, the same keepPerApp/maxAgeDays, and hashes set to the list above.";
			return new PruneResult(true, true, keep, age, plan.scannedApps(), plan.totalScans(),
					plan.candidates().size(), plan.keptCount(), plan.candidates(), selectedHashes, List.of(), List.of(),
					Map.of(), reportsNote(), nextStep);
		}

		if (hashes == null || hashes.isEmpty()) {
			throw new IllegalArgumentException("'hashes' is required when dryRun=false: run prune_scans as a dry run "
					+ "first and pass back the hashes it listed, so you delete exactly what you reviewed.");
		}
		RetentionService.Outcome outcome = retention.delete(hashes, keep, age);
		String nextStep = outcome.failed().isEmpty()
				? "Deleted " + outcome.deleted().size() + " scans. Call list_scans to confirm."
				: outcome.failed().size() + " deletions failed; see 'failed'. The rest were deleted.";
		return new PruneResult(false, true, keep, age, plan.scannedApps(), plan.totalScans(), plan.candidates().size(),
				plan.keptCount(), plan.candidates(), selectedHashes, outcome.deleted(), outcome.skipped(),
				outcome.failed(), reportsNote(), nextStep);
	}

	private static String reportsNote() {
		return "Reports already exported by this server are NOT deleted: <reportDir>/<hash>/*.json and "
				+ "<reportDir>/<hash>.pdf stay in place as an audit trail. Only MobSF's copy of the scan goes.";
	}

	private PruneResult refusal(String message) {
		return new PruneResult(true, false, properties.keepPerApp(), properties.maxAgeDays(), 0, 0, 0, 0, List.of(),
				List.of(), List.of(), List.of(), Map.of(), reportsNote(), message);
	}

}
