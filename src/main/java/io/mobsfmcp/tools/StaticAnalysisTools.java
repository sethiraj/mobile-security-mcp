// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.client.MobsfException;
import io.mobsfmcp.config.MobsfProperties;
import io.mobsfmcp.scans.ScanIndex;
import io.mobsfmcp.upload.FileInputResolver;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Static analysis workflow: upload, scan, scorecard, report, PDF, search, compare, delete. */
@Component
public class StaticAnalysisTools {

	/** Sections of the MobSF report an agent can ask for, mapped to the report's own keys. */
	static final Map<String, List<String>> SECTIONS = Map.of(
			"overview", List.of("title", "app_name", "file_name", "package_name", "version_name", "version_code",
					"size", "md5", "sha256", "min_sdk", "target_sdk", "max_sdk", "average_cvss"),
			"permissions", List.of("permissions", "malware_permissions"),
			"manifest", List.of("manifest_analysis", "exported_count", "browsable_activities"),
			"code", List.of("code_analysis", "niap_analysis", "binary_analysis"),
			"network", List.of("network_security", "domains", "urls", "emails"),
			"secrets", List.of("secrets", "firebase_urls", "playstore_details"),
			"trackers", List.of("trackers"),
			"certificate", List.of("certificate_analysis", "signature_versions"),
			"files", List.of("files", "apkid", "libraries", "activities", "services", "receivers", "providers"));

	private final MobsfClient client;

	private final MobsfProperties properties;

	private final FileInputResolver fileInputResolver;

	/** Dropped whenever this server changes MobSF's scan list, so app lookups stay current. */
	private final ScanIndex scanIndex;

	private final ExecutorService scanExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public StaticAnalysisTools(MobsfClient client, MobsfProperties properties, FileInputResolver fileInputResolver,
			ScanIndex scanIndex) {
		this.client = client;
		this.properties = properties;
		this.fileInputResolver = fileInputResolver;
		this.scanIndex = scanIndex;
	}

	@PreDestroy
	void shutdown() {
		scanExecutor.shutdownNow();
	}

	public record UploadResult(String hash, String fileName, String scanType, String nextStep) {
	}

	public record ScanResult(String hash, String status, long elapsedSeconds, Integer securityScore,
			Map<String, Integer> findingCounts, String lastLog, String nextStep) {
	}

	public record ScanProgress(String hash, String status, String lastStep, List<String> recentSteps) {
	}

	/**
	 * MobSF's AppSec scorecard. {@code securityScore} is 0-100 (higher is better);
	 * {@code trackersDetected} counts trackers found out of {@code trackersChecked}.
	 */
	public record Scorecard(String hash, String appName, String fileName, Integer securityScore,
			Map<String, Integer> findingCounts, List<String> highSeverityTitles, Integer trackersDetected,
			Integer trackersChecked, String mobsfVersion) {
	}

	public record PdfReport(String hash, String path, long sizeBytes) {
	}

	public record ScanComparison(String hashA, String hashB, Integer scoreA, Integer scoreB, Integer scoreDelta,
			List<String> onlyInA, List<String> onlyInB, List<String> inBoth) {
	}

	public record DeleteResult(String hash, boolean deleted, String detail) {
	}

	@McpTool(name = "upload_app",
			description = "Upload an app (apk/xapk/aab/ipa/appx/zip) to MobSF and get its hash. Give exactly one of: "
					+ "'path' (a file inside the server's upload directory, the usual Docker case), 'url' (https), "
					+ "'base64' (small files, needs 'fileName') or 'uploadId' (from POST /uploads in http mode). "
					+ "Uploading does not scan; call scan_app next.",
			annotations = @McpTool.McpAnnotations(title = "Upload app to MobSF", readOnlyHint = false,
					destructiveHint = false, idempotentHint = true, openWorldHint = true))
	public UploadResult uploadApp(
			@McpToolParam(description = "Path to the app file inside the server's upload directory", required = false) String path,
			@McpToolParam(description = "https URL to download the app from (public hosts only)", required = false) String url,
			@McpToolParam(description = "Base64-encoded app file, max 10 MB; requires fileName", required = false) String base64,
			@McpToolParam(description = "Id returned by POST /uploads (http mode)", required = false) String uploadId,
			@McpToolParam(description = "File name to record in MobSF, e.g. app-release.apk", required = false) String fileName) {
		try (FileInputResolver.ResolvedFile file = fileInputResolver.resolve(path, url, base64, uploadId, fileName)) {
			JsonNode response = client.upload(file.resource(), file.fileName());
			String hash = text(response, "hash");
			scanIndex.invalidate();
			return new UploadResult(hash, text(response, "file_name"), text(response, "scan_type"),
					"Run scan_app with hash=" + hash + " to analyse it.");
		}
	}

	@McpTool(name = "scan_app",
			description = "Run MobSF static analysis on an uploaded app and wait for it to finish. Large apps take "
					+ "minutes; if the wait times out the scan keeps running in MobSF - poll scan_status, then call "
					+ "get_scorecard. Returns the security score and finding counts, not the full report.",
			annotations = @McpTool.McpAnnotations(title = "Run static scan", readOnlyHint = false,
					destructiveHint = false, idempotentHint = true, openWorldHint = true))
	public ScanResult scanApp(@McpToolParam(description = "Hash returned by upload_app") String hash,
			@McpToolParam(description = "Re-run analysis even if a report already exists (default false)", required = false) Boolean rescan,
			@McpToolParam(description = "How long to wait, in seconds (default 600, max 3600)", required = false) Integer waitSeconds) {
		requireHash(hash);
		long timeout = Math.min(waitSeconds == null || waitSeconds < 1 ? 600 : waitSeconds, 3600);
		Instant started = Instant.now();
		CompletableFuture<JsonNode> scan = CompletableFuture
			.supplyAsync(() -> client.scan(hash, Boolean.TRUE.equals(rescan)), scanExecutor);
		try {
			requireSynchronousScan(scan.get(timeout, TimeUnit.SECONDS));
		}
		catch (TimeoutException ex) {
			long elapsed = Duration.between(started, Instant.now()).toSeconds();
			return new ScanResult(hash, "running", elapsed, null, Map.of(), lastLog(hash),
					"MobSF is still scanning. Call scan_status with the same hash, then get_scorecard when it is done.");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for the scan of " + hash);
		}
		catch (ExecutionException ex) {
			throw ex.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex.getCause());
		}
		long elapsed = Duration.between(started, Instant.now()).toSeconds();
		scanIndex.invalidate();
		Scorecard scorecard = getScorecard(hash);
		return new ScanResult(hash, "completed", elapsed, scorecard.securityScore(), scorecard.findingCounts(),
				lastLog(hash),
				"Use get_report with a section (overview, permissions, manifest, code, network, secrets, trackers, "
						+ "certificate, files) for details, or download_pdf for a shareable report.");
	}

	@McpTool(name = "scan_status",
			description = "Progress of a MobSF scan: the most recent analysis steps, and whether it has finished.",
			annotations = @McpTool.McpAnnotations(title = "Scan status", readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	public ScanProgress scanStatus(@McpToolParam(description = "Hash of the app") String hash) {
		requireHash(hash);
		List<String> steps = logSteps(client.scanLogs(hash));
		String last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
		boolean finished = last != null && last.toLowerCase(Locale.ROOT).contains("saving to database");
		List<String> recent = steps.size() <= 10 ? steps : steps.subList(steps.size() - 10, steps.size());
		return new ScanProgress(hash, finished ? "completed" : steps.isEmpty() ? "unknown" : "running", last,
				List.copyOf(recent));
	}

	@McpTool(name = "get_scorecard",
			description = "Security score, grade and finding counts for a scanned app. Small - call this before "
					+ "get_report to decide what to look at.",
			annotations = @McpTool.McpAnnotations(title = "Get scorecard", readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	public Scorecard getScorecard(@McpToolParam(description = "Hash of the scanned app") String hash) {
		requireHash(hash);
		JsonNode card = client.scorecard(hash);
		Map<String, Integer> counts = new java.util.LinkedHashMap<>();
		for (String severity : List.of("high", "warning", "info", "secure", "hotspot")) {
			counts.put(severity, card.path(severity).size());
		}
		List<String> highTitles = new ArrayList<>();
		for (JsonNode finding : card.path("high")) {
			highTitles.add(finding.path("title").asString(""));
		}
		return new Scorecard(hash, text(card, "app_name"), text(card, "file_name"),
				intOrNull(card, "security_score"), counts, highTitles, intOrNull(card, "trackers"),
				intOrNull(card, "total_trackers"), text(card, "version"));
	}

	@McpTool(name = "get_report",
			description = "Part of the MobSF static report as JSON. Pick a section: overview, permissions, manifest, "
					+ "code, network, secrets, trackers, certificate, files - or 'all' for the whole report, which is "
					+ "very large. Call get_scorecard first.",
			annotations = @McpTool.McpAnnotations(title = "Get report section", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public JsonNode getReport(@McpToolParam(description = "Hash of the scanned app") String hash,
			@McpToolParam(description = "Section name, or 'all' (default overview)", required = false) String section) {
		requireHash(hash);
		String wanted = section == null || section.isBlank() ? "overview" : section.trim().toLowerCase(Locale.ROOT);
		JsonNode report = client.reportJson(hash);
		if (wanted.equals("all")) {
			return report;
		}
		List<String> keys = SECTIONS.get(wanted);
		if (keys == null) {
			throw new IllegalArgumentException("Unknown section '" + section + "'. Use one of: "
					+ String.join(", ", new java.util.TreeSet<>(SECTIONS.keySet())) + ", all.");
		}
		ObjectNode slice = JsonNodeFactory.instance.objectNode();
		for (String key : keys) {
			if (report.has(key)) {
				slice.set(key, report.get(key));
			}
		}
		return slice;
	}

	@McpTool(name = "download_pdf",
			description = "Write the MobSF PDF report to the server's report directory and return its path.",
			annotations = @McpTool.McpAnnotations(title = "Download PDF report", readOnlyHint = false,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public PdfReport downloadPdf(@McpToolParam(description = "Hash of the scanned app") String hash) {
		requireHash(hash);
		byte[] pdf = client.downloadPdf(hash);
		Path target = properties.reportDir().toAbsolutePath().normalize().resolve(hash + ".pdf");
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, pdf);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not write the PDF to " + target + ": " + ex.getMessage()
					+ ". Check that the report directory is mounted and writable.", ex);
		}
		return new PdfReport(hash, target.toString(), pdf.length);
	}

	@McpTool(name = "compare_scans",
			description = "Compare two scanned apps (for example two releases): score difference and which high "
					+ "severity findings appear in only one of them.",
			annotations = @McpTool.McpAnnotations(title = "Compare two scans", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public ScanComparison compareScans(@McpToolParam(description = "Hash of the first app") String hashA,
			@McpToolParam(description = "Hash of the second app") String hashB) {
		Scorecard a = getScorecard(hashA);
		Scorecard b = getScorecard(hashB);
		List<String> onlyInA = new ArrayList<>(a.highSeverityTitles());
		onlyInA.removeAll(b.highSeverityTitles());
		List<String> onlyInB = new ArrayList<>(b.highSeverityTitles());
		onlyInB.removeAll(a.highSeverityTitles());
		List<String> inBoth = new ArrayList<>(a.highSeverityTitles());
		inBoth.retainAll(b.highSeverityTitles());
		Integer delta = a.securityScore() == null || b.securityScore() == null ? null
				: b.securityScore() - a.securityScore();
		return new ScanComparison(hashA, hashB, a.securityScore(), b.securityScore(), delta, onlyInA, onlyInB, inBoth);
	}

	@McpTool(name = "delete_scan",
			description = "Permanently delete a scan and its uploaded file from MobSF. This cannot be undone.",
			annotations = @McpTool.McpAnnotations(title = "Delete scan", readOnlyHint = false, destructiveHint = true,
					idempotentHint = true, openWorldHint = false))
	public DeleteResult deleteScan(@McpToolParam(description = "Hash of the scan to delete") String hash) {
		requireHash(hash);
		JsonNode response = client.deleteScan(hash);
		scanIndex.invalidate();
		String detail = text(response, "deleted");
		return new DeleteResult(hash, detail != null, detail != null ? detail : response.toString());
	}

	/**
	 * This server only supports synchronous MobSF: {@code /api/v1/scan} is expected to run
	 * the analysis and return the report.
	 * <p>
	 * With {@code MOBSF_ASYNC_ANALYSIS=1} MobSF instead queues the scan and answers with a
	 * {@code task_id} and a message, so there is no report to read. Continuing would call
	 * {@code get_scorecard} on a report that does not exist yet and surface a misleading
	 * "Report not found", which looks like a broken scan rather than a misconfigured server.
	 */
	private static void requireSynchronousScan(JsonNode response) {
		if (response == null || (!response.has("task_id") && !response.has("message"))) {
			return;
		}
		if (response.path("appsec").isObject() || response.has("security_score")) {
			return;    // a real report that merely carries a message field
		}
		String task = response.path("task_id").isNull() ? null : text(response, "task_id");
		String detail = text(response, "message");
		throw new IllegalStateException("MobSF is running in async analysis mode (MOBSF_ASYNC_ANALYSIS=1), which this "
				+ "server does not support. " + (task != null ? "The scan was queued as task " + task + " and no "
						: "The scan was queued and no ")
				+ "report is available to read." + (detail != null ? " MobSF said: " + detail + "." : "")
				+ " Set MOBSF_ASYNC_ANALYSIS=0 on the MobSF container, restart it, and run the scan again.");
	}

	private String lastLog(String hash) {
		try {
			List<String> steps = logSteps(client.scanLogs(hash));
			return steps.isEmpty() ? null : steps.get(steps.size() - 1);
		}
		catch (MobsfException ex) {
			return null;
		}
	}

	private static List<String> logSteps(JsonNode logs) {
		List<String> steps = new ArrayList<>();
		for (JsonNode entry : logs.path("logs")) {
			String status = entry.path("status").asString("");
			String exception = entry.path("exception").asString("");
			steps.add(exception.isBlank() || exception.equals("NA") ? status : status + " (error: " + exception + ")");
		}
		return steps;
	}

	private static void requireHash(String hash) {
		if (hash == null || hash.isBlank()) {
			throw new IllegalArgumentException("'hash' is required; get it from upload_app or list_scans.");
		}
	}

	private static Integer intOrNull(JsonNode node, String field) {
		return node.hasNonNull(field) && node.path(field).isNumber() ? node.path(field).asInt() : null;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}

}
