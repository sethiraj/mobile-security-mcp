// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.util.Json;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Dismissing findings a team has reviewed and accepted.
 * <p>
 * Without this, one accepted risk fails every future CI gate, and the usual workaround is
 * to disable the gate entirely. MobSF stores suppressions **per package name**, so a
 * suppression carries over to later scans of the same app — which is the useful behaviour
 * for a release pipeline, and worth being explicit about.
 */
@Component
public class SuppressionTools {

	static final List<String> TYPES = List.of("code", "manifest");

	private final MobsfClient client;

	public SuppressionTools(MobsfClient client) {
		this.client = client;
	}

	public record SuppressionResult(String hash, String rule, String type, String scope, String status,
			String nextStep) {
	}

	public record Suppression(String rule, String type, String scope, String packageName, List<String> files) {
	}

	public record Suppressions(String hash, int count, List<Suppression> suppressions, String note) {
	}

	@McpTool(name = "suppress_finding",
			description = "Hide a finding that has been reviewed and accepted, so it stops failing scans and CI gates. "
					+ "The rule id comes from a finding's 'id' (e.g. the rule part of static.code.android_insecure_random). "
					+ "scope=rule hides it everywhere in the app; scope=file hides it only in the files it currently "
					+ "matches. MobSF suppresses by package name, so this applies to future scans of the same app too.",
			annotations = @McpTool.McpAnnotations(title = "Suppress a finding", readOnlyHint = false,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public SuppressionResult suppressFinding(@McpToolParam(description = "Hash of the scanned app") String hash,
			@McpToolParam(description = "MobSF rule id, e.g. android_insecure_random or clear_text_traffic") String rule,
			@McpToolParam(description = "code or manifest - which analysis the rule belongs to") String type,
			@McpToolParam(description = "rule (everywhere) or file (only where it matches today); default rule", required = false) String scope) {
		String checkedRule = require(rule, "'rule' is required: the rule id from the finding you want to suppress.");
		String checkedType = requireType(type);
		String checkedScope = scope == null || scope.isBlank() ? "rule" : scope.trim().toLowerCase(Locale.ROOT);

		requireKnownRule(requireHash(hash), checkedRule, checkedType);

		JsonNode response;
		if (checkedScope.equals("file")) {
			if (!checkedType.equals("code")) {
				throw new IllegalArgumentException(
						"scope=file only applies to code rules; manifest findings have no file to scope to.");
			}
			response = client.suppressByFiles(requireHash(hash), checkedRule);
		}
		else if (checkedScope.equals("rule")) {
			response = client.suppressByRule(requireHash(hash), checkedRule, checkedType);
		}
		else {
			throw new IllegalArgumentException("Unknown scope '" + scope + "'. Use 'rule' or 'file'.");
		}
		return new SuppressionResult(hash, checkedRule, checkedType, checkedScope,
				Json.text(response, "status") == null ? "ok" : Json.text(response, "status"),
				"Re-run get_scorecard or get_normalized_report to see the finding counts without it. "
						+ "Use list_suppressions to review, or unsuppress_finding to undo.");
	}

	@McpTool(name = "list_suppressions",
			description = "The findings currently suppressed for an app, so an agent can see what has been dismissed "
					+ "before trusting a clean report.",
			annotations = @McpTool.McpAnnotations(title = "List suppressions", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public Suppressions listSuppressions(@McpToolParam(description = "Hash of the scanned app") String hash) {
		JsonNode response = client.listSuppressions(requireHash(hash));
		List<Suppression> suppressions = new ArrayList<>();
		// MobSF returns one row per (package, type), each holding a list of suppressed rule
		// ids and a map of rule -> files, rather than one row per suppression:
		// {"message":[{"PACKAGE_NAME":"…","SUPPRESS_RULE_ID":["app_is_debuggable"],
		//              "SUPPRESS_FILES":{"rule":["A.java"]},"SUPPRESS_TYPE":"manifest"}]}
		for (JsonNode entry : response.path("message")) {
			String packageName = Json.text(entry, "PACKAGE_NAME");
			String type = Json.text(entry, "SUPPRESS_TYPE");
			for (JsonNode rule : entry.path("SUPPRESS_RULE_ID")) {
				suppressions.add(new Suppression(rule.asString(""), type, "rule", packageName, List.of()));
			}
			entry.path("SUPPRESS_FILES")
				.propertyStream()
				.forEach(file -> suppressions.add(new Suppression(file.getKey(), type, "file", packageName,
						Json.strings(file.getValue()))));
		}
		String note = suppressions.isEmpty()
				? "Nothing is suppressed for this app; the report shows every finding."
				: suppressions.size() + " finding(s) are hidden from this app's reports and scores.";
		return new Suppressions(hash, suppressions.size(), List.copyOf(suppressions), note);
	}

	@McpTool(name = "unsuppress_finding",
			description = "Undo a suppression so the finding is reported again.",
			annotations = @McpTool.McpAnnotations(title = "Remove a suppression", readOnlyHint = false,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public SuppressionResult unsuppressFinding(@McpToolParam(description = "Hash of the scanned app") String hash,
			@McpToolParam(description = "Rule id that was suppressed") String rule,
			@McpToolParam(description = "code or manifest - the same type used when suppressing") String type,
			@McpToolParam(description = "rule or file - the same scope used when suppressing; default rule", required = false) String scope) {
		String checkedRule = require(rule, "'rule' is required: the rule id to stop suppressing.");
		String checkedType = requireType(type);
		String kind = scope == null || scope.isBlank() ? "rule" : scope.trim().toLowerCase(Locale.ROOT);
		if (!kind.equals("rule") && !kind.equals("file")) {
			throw new IllegalArgumentException("Unknown scope '" + scope + "'. Use 'rule' or 'file'.");
		}
		JsonNode response = client.deleteSuppression(requireHash(hash), checkedRule, checkedType, kind);
		return new SuppressionResult(hash, checkedRule, checkedType, kind,
				Json.text(response, "status") == null ? "ok" : Json.text(response, "status"),
				"The finding will appear again in reports and scores for this app.");
	}

	/**
	 * MobSF accepts any rule id and stores it, so a typo suppresses nothing and reports
	 * success — the finding simply stays in every later report. Checking the id against the
	 * scan's own findings turns that silent no-op into a usable error.
	 */
	private void requireKnownRule(String hash, String rule, String type) {
		List<String> known = knownRules(hash, type);
		if (known.isEmpty() || known.contains(rule)) {
			return;    // nothing to check against, or a genuine match
		}
		String suggestions = known.stream()
			.filter(candidate -> candidate.contains(rule) || rule.contains(candidate))
			.limit(3)
			.reduce((a, b) -> a + ", " + b)
			.orElseGet(() -> known.stream().limit(8).reduce((a, b) -> a + ", " + b).orElse(""));
		throw new IllegalArgumentException("No " + type + " finding in this scan uses the rule id '" + rule
				+ "'. MobSF would accept it and silently suppress nothing. Did you mean: " + suggestions
				+ "? Rule ids come from the scan itself - see get_report section " + type + ".");
	}

	/** Rule ids present in this scan, for the given suppression type. */
	private List<String> knownRules(String hash, String type) {
		List<String> rules = new ArrayList<>();
		try {
			JsonNode report = client.reportJson(hash);
			if (type.equals("manifest")) {
				for (JsonNode finding : report.path("manifest_analysis").path("manifest_findings")) {
					String rule = Json.text(finding, "rule");
					if (rule != null) {
						rules.add(rule);
					}
				}
			}
			else {
				report.path("code_analysis").path("findings").propertyStream().forEach(e -> rules.add(e.getKey()));
			}
		}
		catch (RuntimeException ex) {
			return List.of();   // no report yet: let MobSF decide rather than blocking the call
		}
		return List.copyOf(rules);
	}

	private static String requireType(String type) {
		String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
		if (!TYPES.contains(value)) {
			throw new IllegalArgumentException("'type' must be 'code' or 'manifest' (MobSF only suppresses those two); "
					+ "a finding's category tells you which.");
		}
		return value;
	}

	private static String requireHash(String hash) {
		return require(hash, "'hash' is required; get it from upload_app, find_app or list_scans.");
	}

	private static String require(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
		return value.trim();
	}

}
