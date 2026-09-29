// SPDX-License-Identifier: MIT
package io.mobsfmcp.report;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.mobsfmcp.report.model.Finding;
import io.mobsfmcp.report.model.ScanReport;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

/**
 * Converts a normalized {@link ScanReport} to SARIF 2.1.0, the format GitHub code scanning,
 * GitLab and most IDEs read. This is what lets MobSF findings show up as annotations on a
 * pull request.
 */
@Component
public class SarifConverter {

	public static final String SARIF_VERSION = "2.1.0";

	static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";

	private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+");

	private final JsonNodeFactory nodes = JsonNodeFactory.instance;

	public ObjectNode convert(ScanReport report) {
		ObjectNode sarif = nodes.objectNode();
		sarif.put("$schema", SCHEMA);
		sarif.put("version", SARIF_VERSION);

		ObjectNode run = sarif.putArray("runs").addObject();
		ObjectNode driver = run.putObject("tool").putObject("driver");
		driver.put("name", "MobSF");
		driver.put("fullName", "Mobile Security Framework, via mobsf-mcp");
		driver.put("informationUri", "https://github.com/MobSF/Mobile-Security-Framework-MobSF");
		if (report.tool() != null && report.tool().mobsfVersion() != null) {
			driver.put("version", report.tool().mobsfVersion());
		}

		// SARIF wants one rule per finding type, and results referencing them by id.
		Map<String, Finding> rules = new LinkedHashMap<>();
		report.findings().forEach(finding -> rules.putIfAbsent(finding.id(), finding));
		ArrayNode ruleArray = driver.putArray("rules");
		rules.values().forEach(finding -> ruleArray.add(rule(finding)));

		ArrayNode results = run.putArray("results");
		report.findings().forEach(finding -> results.add(result(finding, report)));

		ObjectNode properties = run.putObject("properties");
		if (report.app() != null) {
			properties.put("appHash", report.app().hash());
			properties.put("packageName", report.app().packageName());
		}
		if (report.summary() != null && report.summary().securityScore() != null) {
			properties.put("mobsfSecurityScore", report.summary().securityScore());
		}
		properties.put("scanType", report.scanType() == null ? null : report.scanType().value());
		return sarif;
	}

	private ObjectNode rule(Finding finding) {
		ObjectNode rule = nodes.objectNode();
		rule.put("id", finding.id());
		rule.put("name", toRuleName(finding.id()));
		rule.putObject("shortDescription").put("text", trim(finding.title(), 200));
		if (finding.description() != null && !finding.description().isBlank()) {
			rule.putObject("fullDescription").put("text", trim(finding.description(), 2000));
		}
		if (finding.remediation() != null) {
			rule.putObject("help").put("text", finding.remediation());
		}
		rule.putObject("defaultConfiguration").put("level", level(finding.severity()));

		ArrayNode tags = nodes.arrayNode();
		tags.add("security");
		tags.add(finding.category());
		tags.add(finding.source());
		ObjectNode properties = rule.putObject("properties");
		if (finding.standards() != null) {
			Finding.Standards standards = finding.standards();
			if (standards.cwe() != null) {
				tags.add("external/cwe/" + standards.cwe().split(":")[0].trim().toLowerCase().replace(' ', '-'));
			}
			if (standards.owaspMobile() != null) {
				tags.add(standards.owaspMobile().split(":")[0].trim());
			}
			if (standards.masvs() != null) {
				properties.put("masvs", standards.masvs());
			}
			if (standards.cvss() != null) {
				// GitHub reads this to show a severity on the alert
				properties.put("security-severity", String.valueOf(standards.cvss()));
			}
			if (standards.reference() != null) {
				rule.put("helpUri", standards.reference());
			}
		}
		properties.set("tags", tags);
		return rule;
	}

	private ObjectNode result(Finding finding, ScanReport report) {
		ObjectNode result = nodes.objectNode();
		result.put("ruleId", finding.id());
		result.put("level", level(finding.severity()));
		result.putObject("message").put("text", message(finding));

		ArrayNode locations = result.putArray("locations");
		boolean located = false;
		if (finding.evidence() != null) {
			for (Finding.Evidence evidence : finding.evidence()) {
				if (evidence.file() == null || evidence.file().isBlank()) {
					continue;
				}
				ObjectNode physical = locations.addObject().putObject("physicalLocation");
				physical.putObject("artifactLocation").put("uri", evidence.file());
				Integer line = firstLine(evidence.line());
				if (line != null) {
					physical.putObject("region").put("startLine", line);
				}
				located = true;
			}
		}
		if (!located) {
			// SARIF needs a location for GitHub to display the alert; anchor findings without
			// a file (manifest, TLS, runtime) to the app itself.
			String uri = report.app() != null && report.app().fileName() != null ? report.app().fileName()
					: "application";
			locations.addObject().putObject("physicalLocation").putObject("artifactLocation").put("uri", uri);
		}
		result.putObject("partialFingerprints").put("mobsfFindingId", finding.id());
		return result;
	}

	private static String message(Finding finding) {
		StringBuilder message = new StringBuilder(finding.title() == null ? finding.id() : finding.title());
		if (finding.description() != null && !finding.description().isBlank()) {
			message.append(" — ").append(trim(finding.description(), 800));
		}
		if (finding.remediation() != null && !finding.remediation().isBlank()) {
			message.append(" Fix: ").append(finding.remediation());
		}
		return message.toString();
	}

	/** MobSF severities mapped to the four SARIF levels. */
	static String level(String severity) {
		if (severity == null) {
			return "warning";
		}
		return switch (severity.toLowerCase()) {
			case "high" -> "error";
			case "warning" -> "warning";
			case "info" -> "note";
			default -> "none";     // secure, hotspot
		};
	}

	private static Integer firstLine(String line) {
		if (line == null) {
			return null;
		}
		Matcher matcher = FIRST_NUMBER.matcher(line);
		return matcher.find() ? Integer.valueOf(matcher.group()) : null;
	}

	private static String toRuleName(String id) {
		int lastDot = id.lastIndexOf('.');
		return lastDot < 0 ? id : id.substring(lastDot + 1);
	}

	private static String trim(String value, int max) {
		if (value == null) {
			return "";
		}
		String oneLine = value.replaceAll("\\s+", " ").trim();
		return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
	}

}
