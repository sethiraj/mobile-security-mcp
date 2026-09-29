// SPDX-License-Identifier: MIT
package io.mobsfmcp;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/** WireMock stand-in for the MobSF REST API, shared by the tests. */
public final class MobsfStub {

	public static final String API_KEY = "test-api-key-1234";

	/** A scan whose POST /api/v1/scan never returns in time, to exercise the wait timeout. */
	public static final String SLOW_HASH = "11111111111111111111111111111111";

	/** A second scanned app, for compare_scans. */
	public static final String OTHER_HASH = "22222222222222222222222222222222";

	/** Hash used in the stubbed dynamic report. */
	public static final String HASH_FOR_DYNAMIC = "82ab8b2193b3cfb1c737e3a786be363a";

	public static final String SCANS_JSON = """
			{"content": [
			  {"ANALYZER": "static_analyzer", "SCAN_TYPE": "apk", "FILE_NAME": "diva-beta.apk",
			   "APP_NAME": "Diva", "PACKAGE_NAME": "jakhar.aseem.diva", "VERSION_NAME": "1.0",
			   "MD5": "82ab8b2193b3cfb1c737e3a786be363a", "TIMESTAMP": "2026-09-22T10:15:00Z"}
			], "count": 1, "num_pages": 1}
			""";

	private MobsfStub() {
	}

	public static WireMockServer start() {
		WireMockServer server = new WireMockServer(options().dynamicPort());
		server.start();
		server.stubFor(get(urlPathEqualTo("/api/v1/scans")).withHeader("X-Mobsf-Api-Key", equalTo(API_KEY))
			.willReturn(okJson(SCANS_JSON)));
		return server;
	}

	/** Stubs the upload → scan → scorecard → report → pdf → delete flow for {@code hash}. */
	public static void stubStaticAnalysis(WireMockServer server, String hash) {
		server.stubFor(post(urlPathEqualTo("/api/v1/upload")).withHeader("X-Mobsf-Api-Key", equalTo(API_KEY))
			.willReturn(okJson("""
					{"file_name": "diva-beta.apk", "hash": "%s", "scan_type": "apk"}""".formatted(hash))));

		server.stubFor(post(urlPathEqualTo("/api/v1/scan")).withRequestBody(containing("hash=" + hash))
			.willReturn(okJson(reportJson(hash))));
		server.stubFor(post(urlPathEqualTo("/api/v1/scan")).withRequestBody(containing("hash=" + SLOW_HASH))
			.willReturn(okJson("{}").withFixedDelay(15_000)));

		server.stubFor(post(urlPathEqualTo("/api/v1/scan_logs")).willReturn(okJson("""
				{"logs": [
				  {"timestamp": "2026-09-22 10:15:00", "status": "Generating Hashes", "exception": "NA"},
				  {"timestamp": "2026-09-22 10:15:04", "status": "Code Analysis Started", "exception": "NA"},
				  {"timestamp": "2026-09-22 10:16:01", "status": "Saving to Database", "exception": "NA"}
				]}""")));

		server.stubFor(post(urlPathEqualTo("/api/v1/scorecard")).withRequestBody(containing("hash=" + hash))
			.willReturn(okJson("""
					{"security_score": 42, "app_name": "Diva", "file_name": "diva-beta.apk", "version": "v4.5.4",
					 "total_trackers": 432, "trackers": 3,
					 "high": [{"title": "Debug Enabled For App", "section": "manifest"},
					          {"title": "App uses an insecure random generator", "section": "code"}],
					 "warning": [{"title": "App has a debug certificate", "section": "certificate"}],
					 "info": [], "secure": [], "hotspot": []}""")));
		server.stubFor(post(urlPathEqualTo("/api/v1/scorecard")).withRequestBody(containing("hash=" + OTHER_HASH))
			.willReturn(okJson("""
					{"security_score": 70, "app_name": "Diva", "file_name": "diva-2.0.apk", "version": "v4.5.4",
					 "total_trackers": 432, "trackers": 1,
					 "high": [{"title": "App uses an insecure random generator", "section": "code"},
					          {"title": "Cleartext traffic is enabled", "section": "network"}],
					 "warning": [], "info": [], "secure": [], "hotspot": []}""")));

		server.stubFor(post(urlPathEqualTo("/api/v1/report_json")).withRequestBody(containing("hash=" + hash))
			.willReturn(okJson(reportJson(hash))));

		server.stubFor(post(urlPathEqualTo("/api/v1/download_pdf")).withRequestBody(containing("hash=" + hash))
			.willReturn(aResponse().withHeader("Content-Type", "application/pdf")
				.withBody("%PDF-1.4 fake report".getBytes())));

		server.stubFor(post(urlPathEqualTo("/api/v1/delete_scan")).withRequestBody(containing("hash=" + hash))
			.willReturn(okJson("{\"deleted\": \"scan deleted\"}")));

		server.stubFor(post(urlPathEqualTo("/api/v1/search")).willReturn(okJson(SCANS_JSON)));
	}

	/** Stubs the dynamic analysis endpoints (device, Frida, dynamic report). */
	public static void stubDynamicAnalysis(WireMockServer server, String hash) {
		server.stubFor(get(urlPathEqualTo("/api/v1/dynamic/get_apps")).willReturn(okJson("""
				{"apps": ["jakhar.aseem.diva", "com.android.settings"], "identifier": "host.docker.internal:5555",
				 "android_version": "11", "android_sdk": 30, "android_supported": 30, "proxy_port": 1337,
				 "title": "MobSF Dynamic Analysis", "version": "v4.5.4"}""")));
		server.stubFor(get(urlPathEqualTo("/api/v1/dynamic/get_apps")).withHeader("X-No-Device", equalTo("1"))
			.willReturn(okJson("""
				{"apps": [], "identifier": null, "android_version": null, "android_sdk": null,
				 "android_supported": 30, "version": "v4.5.4"}""")));
		server.stubFor(post(urlPathEqualTo("/api/v1/android/mobsfy"))
			.willReturn(okJson("{\"status\": \"ok\", \"message\": \"Device configured\"}")));
		server.stubFor(post(urlPathEqualTo("/api/v1/dynamic/start_analysis"))
			.willReturn(okJson("{\"status\": \"ok\", \"message\": \"Dynamic analysis started\"}")));
		server.stubFor(post(urlPathEqualTo("/api/v1/dynamic/stop_analysis"))
			.willReturn(okJson("{\"status\": \"ok\", \"message\": \"Data collected\"}")));
		server.stubFor(post(urlPathEqualTo("/api/v1/android/logcat"))
			.willReturn(okJson("{\"logcat\": [\"D/vuln: token abc123\", \"E/vuln: crypto failed\"]}")));
		server.stubFor(post(urlPathEqualTo("/api/v1/android/tls_tests"))
			.willReturn(okJson("{\"status\": \"ok\"}")));
		server.stubFor(post(urlPathEqualTo("/api/v1/android/activity")).willReturn(okJson("{\"status\": \"ok\"}")));
		server.stubFor(post(urlPathEqualTo("/api/v1/android/start_activity")).willReturn(okJson("{\"status\": \"ok\"}")));
		server.stubFor(post(urlPathEqualTo("/api/v1/frida/instrument"))
			.willReturn(okJson("{\"status\": \"ok\", \"message\": \"Frida hooks applied\"}")));
		server.stubFor(post(urlPathEqualTo("/api/v1/frida/api_monitor"))
			.willReturn(okJson("{\"data\": [{\"class\": \"javax.crypto.Cipher\", \"method\": \"getInstance\"}]}")));
		server.stubFor(post(urlPathEqualTo("/api/v1/frida/logs")).willReturn(okJson("{\"data\": \"hook loaded\"}")));
		server.stubFor(post(urlPathEqualTo("/api/v1/frida/list_scripts"))
			.willReturn(okJson("{\"status\": \"ok\", \"files\": [\"api_monitor.js\", \"ssl_pinning_bypass.js\"]}")));
		server.stubFor(post(urlPathEqualTo("/api/v1/android/adb_command"))
			.willReturn(okJson("{\"status\": \"ok\", \"message\": \"package:com.example.vuln\"}")));
		server.stubFor(post(urlPathEqualTo("/api/v1/dynamic/report_json")).withRequestBody(containing("hash=" + hash))
			.willReturn(okJson(dynamicReportJson())));
	}

	/** Shaped after MobSF 4.5.4 dynamic_report context keys. */
	private static String dynamicReportJson() {
		return """
				{"hash": "%s", "package": "com.example.vuln", "version": "v4.5.4", "title": "Dynamic Analysis",
				 "tls_tests": {"tls_misconfigured": {"status": "high", "description": "Cleartext traffic permitted"},
				               "tls_pinning_missing": {"status": "high", "description": "No certificate pinning"},
				               "tls_secure": {"status": "info", "description": "TLS 1.2 in use"}},
				 "apimon": ["javax.crypto.Cipher.getInstance", "java.io.FileOutputStream.write"],
				 "droidmon": ["crypto"], "urls": ["http://api.example.com/v1"],
				 "domains": {"api.example.com": {"bad": "no"}}, "emails": [],
				 "clipboard": ["password123"],
				 "sqlite": ["/data/data/com.example.vuln/databases/creds.db"],
				 "xml": ["/data/data/com.example.vuln/shared_prefs/creds.xml"], "others": [],
				 "screenshots": ["screen1.png"], "activity_tester": ["act1.png"],
				 "exported_activity_tester": [], "trackers": [], "base64_strings": [],
				 "frida_logs": true, "runtime_dependencies": ["libssl.so"]}""".formatted(HASH_FOR_DYNAMIC);
	}

	/**
	 * Serves { /api/v1/scans} as real pages. MobSF returns newest first, so the caller
	 * passes rows in that order; query-parameter stubs are added after the catch-all in
	 * { #start}, and WireMock prefers the more specific later match.
	 */
	public static void stubScanPages(WireMockServer server, java.util.List<String> rows, int pageSize) {
		int total = rows.size();
		int numPages = Math.max(1, (total + pageSize - 1) / pageSize);
		for (int page = 1; page <= numPages; page++) {
			java.util.List<String> slice = rows.subList((page - 1) * pageSize, Math.min(page * pageSize, total));
			String body = "{\"content\": [" + String.join(", ", slice) + "], \"count\": " + total
					+ ", \"num_pages\": " + numPages + "}";
			server.stubFor(get(urlPathEqualTo("/api/v1/scans")).withQueryParam("page", equalTo(String.valueOf(page)))
				.willReturn(okJson(body)));
		}
	}

	/** One recent-scans row. Pass null for fields MobSF would return as an empty string. */
	public static String scanRow(String hash, String fileName, String appName, String packageName, String version,
			String scanType, String timestamp) {
		return """
				{"ANALYZER": "static_analyzer", "SCAN_TYPE": "%s", "FILE_NAME": "%s", "APP_NAME": "%s",
				 "PACKAGE_NAME": "%s", "VERSION_NAME": "%s", "MD5": "%s", "TIMESTAMP": "%s"}"""
			.formatted(orEmpty(scanType), orEmpty(fileName), orEmpty(appName), orEmpty(packageName),
					orEmpty(version), hash, orEmpty(timestamp));
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}

	private static String reportJson(String hash) {
		return """
				{"title": "Static Analysis", "app_name": "Diva", "file_name": "diva-beta.apk",
				 "package_name": "jakhar.aseem.diva", "version_name": "1.0", "md5": "%s",
				 "size": "1.5MB", "min_sdk": "15", "target_sdk": "23",
				 "permissions": {"android.permission.INTERNET": {"status": "normal"}},
				 "manifest_analysis": {"manifest_findings": [{"title": "Debug Enabled", "severity": "high"}]},
				 "code_analysis": {"findings": {"android_insecure_random": {"metadata": {"severity": "high"}}}},
				 "network_security": {"network_findings": []},
				 "secrets": ["api_key = AIzaSyTESTKEY"],
				 "trackers": {"detected_trackers": 3},
				 "certificate_analysis": {"certificate_findings": []},
				 "files": ["classes.dex"]}""".formatted(hash);
	}

}
