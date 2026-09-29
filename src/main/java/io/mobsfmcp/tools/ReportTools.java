// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.report.DynamicReportMapper;
import io.mobsfmcp.report.ReportStore;
import io.mobsfmcp.report.SarifConverter;
import io.mobsfmcp.report.StaticReportMapper;
import io.mobsfmcp.report.model.ScanReport;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Turns MobSF reports into the normalized JSON other agent frameworks consume, and writes
 * them to the report directory.
 */
@Component
public class ReportTools {

	private final MobsfClient client;

	private final StaticReportMapper staticMapper;

	private final DynamicReportMapper dynamicMapper;

	private final ReportStore store;

	private final SarifConverter sarifConverter;

	public ReportTools(MobsfClient client, StaticReportMapper staticMapper, DynamicReportMapper dynamicMapper,
			ReportStore store, SarifConverter sarifConverter) {
		this.client = client;
		this.staticMapper = staticMapper;
		this.dynamicMapper = dynamicMapper;
		this.store = store;
		this.sarifConverter = sarifConverter;
	}

	public record ExportResult(String hash, String scanType, String format, String path, int findingCount,
			Integer securityScore, String schemaVersion) {
	}

	@McpTool(name = "export_report",
			description = "Write a scan report to the server's report directory as JSON and return its path. "
					+ "format=normalized (default) gives the stable mobsf-mcp-report/v1 schema that any framework can "
					+ "parse; format=raw gives MobSF's own JSON; format=sarif gives SARIF 2.1.0 for GitHub code "
					+ "scanning and IDEs. type is static, dynamic or combined (combined needs both scans to have run).",
			annotations = @McpTool.McpAnnotations(title = "Export report as JSON", readOnlyHint = false,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public ExportResult exportReport(@McpToolParam(description = "Hash of the scanned app") String hash,
			@McpToolParam(description = "static | dynamic | combined (default static)", required = false) String type,
			@McpToolParam(description = "normalized | raw | sarif (default normalized)", required = false) String format) {
		if (hash == null || hash.isBlank()) {
			throw new IllegalArgumentException("'hash' is required; get it from upload_app or list_scans.");
		}
		ScanReport.ScanType scanType = parseType(type);
		ReportStore.Format wanted = ReportStore.Format.parse(format);

		if (wanted == ReportStore.Format.RAW) {
			JsonNode raw = scanType == ScanReport.ScanType.DYNAMIC ? client.dynamicReport(hash)
					: client.reportJson(hash);
			Path path = store.writeRaw(hash, scanType, raw);
			return new ExportResult(hash, scanType.name().toLowerCase(Locale.ROOT), "raw", path.toString(),
					raw.path("appsec").path("high").size(), raw.path("appsec").path("security_score").asInt(0), null);
		}
		if (wanted == ReportStore.Format.SARIF) {
			ScanReport report = build(hash, scanType);
			Path path = store.writeSarif(hash, scanType, sarifConverter.convert(report));
			return new ExportResult(hash, scanType.name().toLowerCase(Locale.ROOT), "sarif", path.toString(),
					report.findings().size(), report.summary().securityScore(), SarifConverter.SARIF_VERSION);
		}
		ScanReport report = build(hash, scanType);
		Path path = store.write(hash, scanType, report);
		return new ExportResult(hash, scanType.name().toLowerCase(Locale.ROOT), "normalized", path.toString(),
				report.findings().size(), report.summary().securityScore(), report.schemaVersion());
	}

	@McpTool(name = "get_normalized_report",
			description = "The normalized report (mobsf-mcp-report/v1) for a scanned app, returned directly instead of "
					+ "written to disk. Optionally filter by severity (high, warning, info) to keep it small.",
			annotations = @McpTool.McpAnnotations(title = "Get normalized report", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public ScanReport getNormalizedReport(@McpToolParam(description = "Hash of the scanned app") String hash,
			@McpToolParam(description = "Only keep findings of this severity, e.g. high", required = false) String severity) {
		if (hash == null || hash.isBlank()) {
			throw new IllegalArgumentException("'hash' is required; get it from upload_app or list_scans.");
		}
		ScanReport report = staticMapper.map(hash, client.reportJson(hash));
		if (severity == null || severity.isBlank()) {
			return report;
		}
		String wanted = severity.trim().toLowerCase(Locale.ROOT);
		List<io.mobsfmcp.report.model.Finding> filtered = report.findings()
			.stream()
			.filter(finding -> wanted.equalsIgnoreCase(finding.severity()))
			.toList();
		return new ScanReport(report.schemaVersion(), report.scanType(), report.generatedAt(), report.tool(),
				report.app(), report.summary(), filtered, report.staticAnalysis(), report.dynamicAnalysis(),
				report.rawRef());
	}

	/** Builds a report of the requested type; combined needs both scans to exist in MobSF. */
	private ScanReport build(String hash, ScanReport.ScanType scanType) {
		return switch (scanType) {
			case STATIC -> staticMapper.map(hash, client.reportJson(hash));
			case DYNAMIC -> dynamicMapper.map(hash, client.dynamicReport(hash));
			case COMBINED -> dynamicMapper.combine(staticMapper.map(hash, client.reportJson(hash)),
					dynamicMapper.map(hash, client.dynamicReport(hash)));
		};
	}

	private static ScanReport.ScanType parseType(String type) {
		if (type == null || type.isBlank()) {
			return ScanReport.ScanType.STATIC;
		}
		return switch (type.trim().toLowerCase(Locale.ROOT)) {
			case "static" -> ScanReport.ScanType.STATIC;
			case "dynamic" -> ScanReport.ScanType.DYNAMIC;
			case "combined" -> ScanReport.ScanType.COMBINED;
			default -> throw new IllegalArgumentException(
					"Unknown type '" + type + "'. Use 'static', 'dynamic' or 'combined'.");
		};
	}

}
