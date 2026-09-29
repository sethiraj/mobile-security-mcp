// SPDX-License-Identifier: MIT
package io.mobsfmcp.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.mobsfmcp.report.model.Finding;
import io.mobsfmcp.report.model.ScanReport;
import tools.jackson.databind.JsonNode;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Maps MobSF's {@code dynamic/report_json} onto the normalized {@link ScanReport}, and
 * merges a static and a dynamic report into a combined one.
 * <p>
 * MobSF's dynamic report carries: {@code tls_tests}, {@code apimon}, {@code droidmon},
 * {@code urls}, {@code domains}, {@code emails}, {@code sqlite}, {@code xml},
 * {@code clipboard}, {@code screenshots}, {@code activity_tester},
 * {@code exported_activity_tester}, {@code trackers} and {@code runtime_dependencies}.
 */
@Component
public class DynamicReportMapper {

	private final String serverVersion;

	public DynamicReportMapper(Environment environment) {
		this.serverVersion = environment.getProperty("spring.ai.mcp.server.version", "unknown");
	}

	public ScanReport map(String hash, JsonNode report) {
		List<Finding> findings = new ArrayList<>();
		addTlsFindings(report, findings);
		addClipboardFindings(report, findings);

		ScanReport.DynamicDetails details = new ScanReport.DynamicDetails(tlsTests(report), strings(report.path("apimon")),
				strings(report.path("urls")), fileArtifacts(report), strings(report.path("droidmon")),
				strings(report.path("screenshots")));

		return new ScanReport(ScanReport.SCHEMA_VERSION, ScanReport.ScanType.DYNAMIC, Instant.now(),
				new ScanReport.ToolInfo("mobsf-mcp", serverVersion, text(report, "version")),
				new ScanReport.AppInfo(hash, null, null, text(report, "package"), null, null, "android", "dynamic",
						null, null, null, null),
				new ScanReport.Summary(null, severityCounts(findings), null, null, null), List.copyOf(findings), null,
				details, null);
	}

	/** Merges static and dynamic reports; findings are deduplicated by id, static first. */
	public ScanReport combine(ScanReport staticReport, ScanReport dynamicReport) {
		Map<String, Finding> byId = new LinkedHashMap<>();
		staticReport.findings().forEach(finding -> byId.putIfAbsent(finding.id(), finding));
		dynamicReport.findings().forEach(finding -> byId.putIfAbsent(finding.id(), finding));
		List<Finding> merged = List.copyOf(byId.values());

		ScanReport.Summary staticSummary = staticReport.summary();
		ScanReport.Summary summary = new ScanReport.Summary(staticSummary.securityScore(), severityCounts(merged),
				staticSummary.averageCvss(), staticSummary.trackersDetected(), staticSummary.trackersChecked());

		return new ScanReport(ScanReport.SCHEMA_VERSION, ScanReport.ScanType.COMBINED, Instant.now(),
				staticReport.tool(), staticReport.app(), summary, merged, staticReport.staticAnalysis(),
				dynamicReport.dynamicAnalysis(), staticReport.rawRef());
	}

	private void addTlsFindings(JsonNode report, List<Finding> findings) {
		JsonNode tls = report.path("tls_tests");
		if (tls.isArray()) {
			int index = 0;
			for (JsonNode node : tls) {
				findings.add(new Finding("dynamic.tls." + index++, "dynamic", "tls",
						node.path("severity").asString("info"), node.path("title").asString("TLS test"),
						node.path("description").asString(""), List.of(), null, null));
			}
			return;
		}
		tls.propertyStream().forEach(entry -> {
			JsonNode value = entry.getValue();
			// MobSF reports either a boolean-ish flag or an object with status/description
			boolean failed = value.isBoolean() ? value.asBoolean()
					: "high".equalsIgnoreCase(value.path("status").asString(""))
							|| "failed".equalsIgnoreCase(value.path("status").asString(""));
			findings.add(new Finding("dynamic.tls." + entry.getKey(), "dynamic", "tls", failed ? "high" : "info",
					entry.getKey().replace('_', ' '),
					value.isObject() ? value.path("description").asString("") : value.asString(""), List.of(), null,
					null));
		});
	}

	private void addClipboardFindings(JsonNode report, List<Finding> findings) {
		List<String> clipboard = strings(report.path("clipboard"));
		if (!clipboard.isEmpty()) {
			findings.add(new Finding("dynamic.runtime.clipboard", "dynamic", "runtime", "warning",
					"App wrote to the clipboard", String.join("\n", clipboard), List.of(), null,
					"Do not put credentials or tokens on the shared clipboard."));
		}
	}

	private static Map<String, Object> tlsTests(JsonNode report) {
		Map<String, Object> tls = new LinkedHashMap<>();
		report.path("tls_tests").propertyStream().forEach(entry -> tls.put(entry.getKey(), entry.getValue().toString()));
		return Map.copyOf(tls);
	}

	private static List<String> fileArtifacts(JsonNode report) {
		Set<String> files = new LinkedHashSet<>();
		files.addAll(strings(report.path("sqlite")));
		files.addAll(strings(report.path("xml")));
		files.addAll(strings(report.path("others")));
		return List.copyOf(files);
	}

	private static Map<String, Integer> severityCounts(List<Finding> findings) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String severity : List.of("high", "warning", "info", "secure", "hotspot")) {
			counts.put(severity, (int) findings.stream().filter(f -> severity.equalsIgnoreCase(f.severity())).count());
		}
		return counts;
	}

	private static List<String> strings(JsonNode node) {
		List<String> values = new ArrayList<>();
		if (node.isObject()) {
			node.propertyStream().forEach(entry -> values.add(entry.getKey()));
			return List.copyOf(values);
		}
		for (JsonNode item : node) {
			values.add(item.isTextual() ? item.asString("") : item.toString());
		}
		return List.copyOf(values);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.isTextual() ? value.asString() : value.toString();
	}

}
