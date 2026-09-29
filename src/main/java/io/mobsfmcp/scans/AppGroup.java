// SPDX-License-Identifier: MIT
package io.mobsfmcp.scans;

import java.util.List;

/**
 * Every scan MobSF holds for one app, newest first. MobSF has no API that answers "which
 * scans do I have for this app" — its search returns a single match — so this is built
 * client-side by {@link ScanIndex}.
 */
public record AppGroup(String appKey, String appName, String packageName, String scanType, int scanCount,
		List<ScanSummary> versions) {

	static AppGroup of(String appKey, List<ScanSummary> versions) {
		ScanSummary newest = versions.get(0);
		boolean sameType = versions.stream().allMatch(scan -> java.util.Objects.equals(scan.scanType(), newest.scanType()));
		return new AppGroup(appKey, newest.appName(), newest.packageName(), sameType ? newest.scanType() : "mixed",
				versions.size(), List.copyOf(versions));
	}

	/** The scan an agent means when it says "this app" without naming a version. */
	public ScanSummary newest() {
		return versions.get(0);
	}

	/** Finds a scan by its VERSION_NAME, newest first when a version was scanned twice. */
	public java.util.Optional<ScanSummary> version(String version) {
		return versions.stream().filter(scan -> version.equalsIgnoreCase(scan.version())).findFirst();
	}

}
