// SPDX-License-Identifier: MIT
package io.mobsfmcp.scans;

import io.mobsfmcp.util.Json;
import tools.jackson.databind.JsonNode;

/**
 * One row from MobSF's recent-scans list. {@code hash} is the MD5 that every other tool
 * takes; MobSF keys scans by the file's content hash, so re-uploading an identical file
 * updates this record while a different build with the same name becomes a new one.
 * <p>
 * Fields MobSF has no value for are {@code null}, never {@code ""} — see {@link Json}.
 */
public record ScanSummary(String hash, String fileName, String appName, String packageName, String version,
		String scanType, String analyzer, String timestamp) {

	public static ScanSummary from(JsonNode node) {
		return new ScanSummary(Json.text(node, "MD5"), Json.text(node, "FILE_NAME"), Json.text(node, "APP_NAME"),
				Json.text(node, "PACKAGE_NAME"), Json.text(node, "VERSION_NAME"), Json.text(node, "SCAN_TYPE"),
				Json.text(node, "ANALYZER"), Json.text(node, "TIMESTAMP"));
	}

	/**
	 * Key used to group scans of the same app. Package name first, then app name, then
	 * file name — a source-zip scan has no package name at all. Blank values fall through,
	 * which is what stops unrelated apps collapsing into one empty-keyed group.
	 */
	public String appKey() {
		String key = Json.firstNonBlank(packageName, appName, fileName);
		return key != null ? key : hash;
	}

	/**
	 * Finer key than {@link #appKey()}, used to protect the newest scan of each app when a
	 * group over-merges: grouping falls back to the file name, so two unrelated apps with
	 * no package name can land in one group.
	 * <p>
	 * Deliberately excludes the file name, which changes from release to release
	 * ({@code demo-1.0.apk}, {@code demo-2.0.apk}) — including it would make every version
	 * its own identity and protect the whole history from pruning.
	 */
	public String identityKey() {
		return (packageName == null ? "" : packageName) + '|' + (appName == null ? "" : appName);
	}

	/** True when any of the app's names contains {@code query}, case-insensitively. */
	public boolean matches(String query) {
		String needle = query.toLowerCase();
		return contains(packageName, needle) || contains(appName, needle) || contains(fileName, needle)
				|| (hash != null && hash.equalsIgnoreCase(query));
	}

	private static boolean contains(String value, String lowercaseNeedle) {
		return value != null && value.toLowerCase().contains(lowercaseNeedle);
	}

}
