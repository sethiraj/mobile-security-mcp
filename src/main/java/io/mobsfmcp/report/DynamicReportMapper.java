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
				screenshots(report), strings(report.path("domains")));

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
		// A real run returns a flat object of booleans, each named for the problem it tests:
		// {"tls_misconfigured":false,"no_tls_pin_or_transparency":false,
		//  "pin_or_transparency_bypassed":false,"has_cleartext":true}
		// true means the problem is present, so false is a pass, not an unknown.
		tls.propertyStream().forEach(entry -> {
			JsonNode value = entry.getValue();
			boolean failed = value.isBoolean() ? value.asBoolean()
					: "high".equalsIgnoreCase(value.path("status").asString(""))
							|| "failed".equalsIgnoreCase(value.path("status").asString(""));
			Map<String, String> titles = failed ? TLS_FAILED_TITLES : TLS_PASSED_TITLES;
			findings.add(new Finding("dynamic.tls." + entry.getKey(), "dynamic", "tls", failed ? "high" : "secure",
					titles.getOrDefault(entry.getKey(), entry.getKey().replace('_', ' ')),
					value.isObject() ? value.path("description").asString("")
							: failed ? TLS_FAILED.getOrDefault(entry.getKey(), "The app failed this TLS check.")
									: TLS_PASSED.getOrDefault(entry.getKey(), "The app passed this TLS check."),
					List.of(), null, failed ? TLS_FIXES.get(entry.getKey()) : null));
		});
	}

	/**
	 * MobSF names each check after the problem it looks for, so the name only describes
	 * the result when the check fails. A title has to follow the outcome: reporting
	 * {@code severity: secure} under the title "TLS is misconfigured" reads to an agent
	 * summarizing the report as though the problem were present.
	 */
	private static final Map<String, String> TLS_FAILED_TITLES = Map.of(
			"tls_misconfigured", "TLS is misconfigured",
			"no_tls_pin_or_transparency", "No certificate pinning or transparency",
			"pin_or_transparency_bypassed", "Certificate pinning was bypassed",
			"has_cleartext", "App sends cleartext (unencrypted) traffic");

	private static final Map<String, String> TLS_PASSED_TITLES = Map.of(
			"tls_misconfigured", "TLS is configured correctly",
			"no_tls_pin_or_transparency", "Certificate pinning or transparency is in use",
			"pin_or_transparency_bypassed", "Certificate pinning held under attack",
			"has_cleartext", "No cleartext traffic observed");

	private static final Map<String, String> TLS_FAILED = Map.of(
			"tls_misconfigured", "The app accepts TLS connections it should reject.",
			"no_tls_pin_or_transparency", "The app does not pin certificates or use certificate transparency.",
			"pin_or_transparency_bypassed", "Pinning was bypassed at runtime, so traffic can be intercepted.",
			"has_cleartext", "Traffic was observed in cleartext and can be read or modified in transit.");

	private static final Map<String, String> TLS_PASSED = Map.of(
			"tls_misconfigured", "The app rejected the invalid certificates MobSF offered it.",
			"no_tls_pin_or_transparency", "The app pins certificates or relies on certificate transparency.",
			"pin_or_transparency_bypassed", "Pinning was not bypassed during this run.",
			"has_cleartext", "No unencrypted traffic was observed during this run.");

	private static final Map<String, String> TLS_FIXES = Map.of(
			"tls_misconfigured", "Reject invalid certificates; do not disable hostname verification.",
			"no_tls_pin_or_transparency", "Pin the certificates of the hosts the app talks to.",
			"pin_or_transparency_bypassed", "Pinning alone will not stop a rooted device; treat the client as untrusted.",
			"has_cleartext", "Use HTTPS everywhere and set android:usesCleartextTraffic=false.");

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

	/**
	 * Screenshots taken during the run. The {@code screenshots} array is empty in practice;
	 * the activity tester's images arrive as {@code {"act-1.png": ".HomeScreenActivity"}},
	 * so the file name and the activity it shows are both worth keeping.
	 */
	private static List<String> screenshots(JsonNode report) {
		Set<String> images = new LinkedHashSet<>(strings(report.path("screenshots")));
		for (String key : List.of("activity_tester", "exported_activity_tester")) {
			report.path(key)
				.propertyStream()
				.forEach(entry -> images.add(entry.getKey() + " (" + entry.getValue().asString("") + ")"));
		}
		return List.copyOf(images);
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
