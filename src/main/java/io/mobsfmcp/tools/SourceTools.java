// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.util.List;
import java.util.Locale;

import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.util.Json;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Reading the code behind a finding.
 * <p>
 * Findings carry {@code evidence.file} and {@code evidence.line}, but without the source
 * an agent can report "insecure random at MainActivity.java:6" and go no further. This
 * closes the loop between a finding and a fix.
 */
@Component
public class SourceTools {

	/** File types MobSF's Android viewer accepts; anything else is treated as iOS. */
	static final List<String> ANDROID_TYPES = List.of("eclipse", "studio", "apk", "java", "smali", "xml");

	/** Big enough for a real source file, small enough not to swamp an agent's context. */
	static final int MAX_CHARS = 60_000;

	private final MobsfClient client;

	public SourceTools(MobsfClient client) {
		this.client = client;
	}

	public record SourceFile(String hash, String file, String type, int lineCount, boolean truncated, String source,
			String note) {
	}

	@McpTool(name = "view_source",
			description = "Read one source file from a scanned app, so a finding can be understood and fixed. The file "
					+ "path comes from a finding's evidence.file. type is how MobSF should look the file up: java or "
					+ "smali for decompiled APK code, studio or eclipse for source-zip scans, xml for manifests and "
					+ "resources. Long files are truncated.",
			annotations = @McpTool.McpAnnotations(title = "View source file", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public SourceFile viewSource(@McpToolParam(description = "Hash of the scanned app") String hash,
			@McpToolParam(description = "File path as it appears in a finding's evidence, e.g. com/example/app/Main.java") String file,
			@McpToolParam(description = "java | smali | studio | eclipse | xml | apk (default java)", required = false) String type) {
		String checkedHash = require(hash, "'hash' is required; get it from upload_app, find_app or list_scans.");
		String checkedFile = require(file, "'file' is required: the path from a finding's evidence.file.");
		String checkedType = type == null || type.isBlank() ? "java" : type.trim().toLowerCase(Locale.ROOT);

		JsonNode response = client.viewSource(checkedHash, checkedFile, checkedType);
		String source = Json.text(response, "data");
		if (source == null) {
			source = Json.text(response, "file") != null ? Json.text(response, "file") : "";
		}
		boolean truncated = source.length() > MAX_CHARS;
		String body = truncated ? source.substring(0, MAX_CHARS) : source;
		int lines = body.isEmpty() ? 0 : body.split("\r?\n", -1).length;

		String note = body.isEmpty()
				? "MobSF returned no content for this path. Check the file path from the finding's evidence, and that "
						+ "'type' matches the scan (java/smali for an APK, studio/eclipse for a source zip)."
				: truncated ? "Truncated at " + MAX_CHARS + " characters." : null;
		return new SourceFile(checkedHash, checkedFile, checkedType, lines, truncated, body, note);
	}

	private static String require(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
		return value.trim();
	}

}
