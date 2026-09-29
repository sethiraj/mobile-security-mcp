// SPDX-License-Identifier: MIT
package io.mobsfmcp.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.mobsfmcp.report.model.Finding;
import io.mobsfmcp.report.model.ScanReport;
import tools.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/**
 * Maps MobSF's {@code report_json} onto the normalized {@link ScanReport}. All knowledge
 * of MobSF's own JSON layout lives here, so a change in MobSF only touches this class.
 * Every lookup is defensive: MobSF omits whole sections depending on the scan type
 * (apk, ipa or source zip).
 */
@Component
public class StaticReportMapper {

	private final String serverVersion;

	public StaticReportMapper(org.springframework.core.env.Environment environment) {
		this.serverVersion = environment.getProperty("spring.ai.mcp.server.version", "unknown");
	}

	public ScanReport map(String hash, JsonNode report) {
		JsonNode appsec = report.path("appsec");
		List<Finding> findings = new ArrayList<>();
		addManifestFindings(report, findings);
		addCodeFindings(report, findings);
		addNetworkFindings(report, findings);
		addCertificateFindings(report, findings);
		addSecretFindings(report, findings);
		addTrackerFindings(report, findings);

		return new ScanReport(ScanReport.SCHEMA_VERSION, ScanReport.ScanType.STATIC, Instant.now(),
				new ScanReport.ToolInfo("mobsf-mcp", serverVersion, mobsfVersion(report, appsec)), appInfo(hash, report),
				summary(report, appsec), List.copyOf(findings), staticDetails(report), null, null);
	}

	/** MobSF reports its version in the scorecard block, and as "version" on the report itself. */
	private static String mobsfVersion(JsonNode report, JsonNode appsec) {
		String fromAppsec = text(appsec, "version");
		return fromAppsec != null ? fromAppsec : text(report, "version");
	}

	private ScanReport.AppInfo appInfo(String hash, JsonNode report) {
		return new ScanReport.AppInfo(hash, text(report, "file_name"), text(report, "app_name"),
				text(report, "package_name"), text(report, "version_name"), text(report, "version_code"),
				platform(report), text(report, "app_type"), text(report, "min_sdk"), text(report, "target_sdk"),
				text(report, "sha256"), text(report, "size"));
	}

	private static String platform(JsonNode report) {
		if (report.has("ipa") || "ios".equalsIgnoreCase(text(report, "app_type"))) {
			return "ios";
		}
		return report.isEmpty() ? null : "android";
	}

	private ScanReport.Summary summary(JsonNode report, JsonNode appsec) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String severity : List.of("high", "warning", "info", "secure", "hotspot")) {
			counts.put(severity, appsec.path(severity).size());
		}
		JsonNode trackers = report.path("trackers");
		return new ScanReport.Summary(integer(appsec, "security_score"), counts,
				report.path("average_cvss").isNumber() ? report.path("average_cvss").asDouble() : null,
				integer(trackers, "detected_trackers"), integer(trackers, "total_trackers"));
	}

	private ScanReport.StaticDetails staticDetails(JsonNode report) {
		List<ScanReport.Permission> permissions = new ArrayList<>();
		JsonNode permissionsNode = report.path("permissions");
		permissionsNode.propertyStream()
			.forEach(entry -> permissions.add(new ScanReport.Permission(entry.getKey(),
					text(entry.getValue(), "status"), text(entry.getValue(), "description"))));

		Map<String, Integer> exported = new LinkedHashMap<>();
		JsonNode exportedCount = report.path("exported_count");
		exportedCount.propertyStream()
			.forEach(entry -> exported.put(entry.getKey(), entry.getValue().asInt(0)));

		List<String> trackers = new ArrayList<>();
		for (JsonNode tracker : report.path("trackers").path("trackers")) {
			trackers.add(text(tracker, "name"));
		}

		return new ScanReport.StaticDetails(List.copyOf(permissions), Map.copyOf(exported),
				strings(report.path("secrets")), strings(report.path("domains").propertyNames()),
				strings(report.path("urls")), strings(report.path("emails")), List.copyOf(trackers),
				text(report.path("certificate_analysis"), "certificate_info"), strings(report.path("files")));
	}

	private void addManifestFindings(JsonNode report, List<Finding> findings) {
		for (JsonNode node : report.path("manifest_analysis").path("manifest_findings")) {
			String rule = text(node, "rule");
			findings.add(new Finding("static.manifest." + orUnknown(rule), "static", "manifest", text(node, "severity"),
					text(node, "title"), text(node, "description"), components(node), null, null));
		}
	}

	private void addCodeFindings(JsonNode report, List<Finding> findings) {
		report.path("code_analysis").path("findings").propertyStream().forEach(entry -> {
			JsonNode metadata = entry.getValue().path("metadata");
			List<Finding.Evidence> evidence = new ArrayList<>();
			entry.getValue()
				.path("files")
				.propertyStream()
				.forEach(file -> evidence.add(new Finding.Evidence(file.getKey(), file.getValue().asString(""), null)));
			findings.add(new Finding("static.code." + entry.getKey(), "static", "code", text(metadata, "severity"),
					entry.getKey().replace('_', ' '), text(metadata, "description"), List.copyOf(evidence),
					standards(metadata), null));
		});
	}

	private void addNetworkFindings(JsonNode report, List<Finding> findings) {
		int index = 0;
		for (JsonNode node : report.path("network_security").path("network_findings")) {
			List<Finding.Evidence> evidence = new ArrayList<>();
			for (JsonNode scope : node.path("scope")) {
				evidence.add(new Finding.Evidence(null, null, scope.asString("")));
			}
			findings.add(new Finding("static.network." + index++, "static", "network", text(node, "severity"),
					text(node, "title"), text(node, "description"), List.copyOf(evidence), null, null));
		}
	}

	private void addCertificateFindings(JsonNode report, List<Finding> findings) {
		int index = 0;
		for (JsonNode node : report.path("certificate_analysis").path("certificate_findings")) {
			// MobSF reports these as [severity, description, title] triples
			if (node.isArray() && node.size() >= 3) {
				findings.add(new Finding("static.certificate." + index++, "static", "certificate",
						node.get(0).asString(""), node.get(2).asString(""), node.get(1).asString(""), List.of(), null,
						null));
			}
		}
	}

	private void addSecretFindings(JsonNode report, List<Finding> findings) {
		int index = 0;
		for (JsonNode secret : report.path("secrets")) {
			findings.add(new Finding("static.secrets." + index++, "static", "secrets", "warning",
					"Possible hardcoded secret", secret.asString(""), List.of(), null,
					"Move the value out of the app: use a server-side exchange or the platform keystore."));
		}
	}

	private void addTrackerFindings(JsonNode report, List<Finding> findings) {
		for (JsonNode tracker : report.path("trackers").path("trackers")) {
			findings.add(new Finding("static.tracker." + orUnknown(text(tracker, "name")), "static", "tracker", "info",
					"Tracker: " + text(tracker, "name"), text(tracker, "categories"),
					List.of(new Finding.Evidence(null, null, text(tracker, "url"))), null, null));
		}
	}

	private static List<Finding.Evidence> components(JsonNode node) {
		String component = text(node, "component");
		String name = text(node, "name");
		if (component == null && name == null) {
			return List.of();
		}
		return List.of(new Finding.Evidence(null, null, name == null ? component : name));
	}

	private static Finding.Standards standards(JsonNode metadata) {
		if (metadata.isMissingNode() || metadata.isEmpty()) {
			return null;
		}
		return new Finding.Standards(text(metadata, "cwe"), text(metadata, "owasp-mobile"), text(metadata, "masvs"),
				metadata.path("cvss").isNumber() ? metadata.path("cvss").asDouble() : null, text(metadata, "ref"));
	}

	private static List<String> strings(JsonNode node) {
		List<String> values = new ArrayList<>();
		for (JsonNode item : node) {
			values.add(item.isTextual() ? item.asString("") : item.toString());
		}
		return List.copyOf(values);
	}

	private static List<String> strings(java.util.Collection<String> names) {
		return List.copyOf(names);
	}

	private static String orUnknown(String value) {
		return value == null || value.isBlank() ? "unknown" : value;
	}

	private static Integer integer(JsonNode node, String field) {
		return node.path(field).isNumber() ? node.path(field).asInt() : null;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.isTextual() ? value.asString() : value.toString();
	}

}
