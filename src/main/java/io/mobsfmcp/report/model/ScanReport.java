// SPDX-License-Identifier: MIT
package io.mobsfmcp.report.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Normalized scan report: a stable shape any agent framework can parse without knowing
 * MobSF's own JSON. Static and dynamic findings share one {@link Finding} shape, so
 * downstream tooling (CI gates, issue trackers, dashboards) is written once.
 * <p>
 * The schema is versioned by {@link #SCHEMA_VERSION} and published as
 * {@code schemas/mobsf-mcp-report.v1.json}. MobSF's untouched output stays available
 * through {@link #rawRef()} and the {@code format=raw} export.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScanReport(String schemaVersion, ScanType scanType, Instant generatedAt, ToolInfo tool, AppInfo app,
		Summary summary, List<Finding> findings, StaticDetails staticAnalysis, DynamicDetails dynamicAnalysis,
		RawRef rawRef) {

	public static final String SCHEMA_VERSION = "mobsf-mcp-report/v1";

	public enum ScanType {

		STATIC, DYNAMIC, COMBINED;

		/** Serialized lowercase, so consumers see "static" rather than "STATIC". */
		@com.fasterxml.jackson.annotation.JsonValue
		public String value() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

	}

	/** Which tools produced the report. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolInfo(String name, String version, String mobsfVersion) {
	}

	/** The analysed app. Fields MobSF does not provide for a given scan type stay null. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AppInfo(String hash, String fileName, String appName, String packageName, String version,
			String versionCode, String platform, String scanType, String minSdk, String targetSdk, String sha256,
			String size) {
	}

	/**
	 * @param securityScore MobSF's score, 0-100, higher is better
	 * @param findingCounts findings per severity: high, warning, info, secure, hotspot
	 * @param trackersDetected trackers found in the app
	 * @param trackersChecked size of MobSF's tracker database
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Summary(Integer securityScore, Map<String, Integer> findingCounts, Double averageCvss,
			Integer trackersDetected, Integer trackersChecked) {
	}

	/** Extra static-analysis detail that is not a finding. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record StaticDetails(List<Permission> permissions, Map<String, Integer> exportedComponentCounts,
			List<String> secrets, List<String> domains, List<String> urls, List<String> emails,
			List<String> trackers, String certificateInfo, List<String> files) {
	}

	/** Filled from M6 onwards; null in static-only reports. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record DynamicDetails(Map<String, Object> tlsTests, List<String> apiMonitor, List<String> httpTraffic,
			List<String> fileSystem, List<String> logcatHighlights, List<String> screenshots) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Permission(String name, String status, String description) {
	}

	/** Where MobSF's untouched JSON was written, when it was exported. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RawRef(String staticPath, String dynamicPath) {
	}

}
