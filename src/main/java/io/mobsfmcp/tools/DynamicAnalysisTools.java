// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.client.MobsfException;
import io.mobsfmcp.config.MobsfProperties;
import io.mobsfmcp.util.Json;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Dynamic analysis: running the app on a real Android device or emulator that MobSF
 * controls over adb. Everything here needs {@code MOBSF_ANALYZER_IDENTIFIER} set on the
 * MobSF container and a rooted, MobSFy-ed device; {@link #deviceStatus()} says what is
 * missing when it is not.
 */
@Component
public class DynamicAnalysisTools {

	private final MobsfClient client;

	private final MobsfProperties properties;

	public DynamicAnalysisTools(MobsfClient client, MobsfProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	/**
	 * @param identifier the device MobSF is bound to, null when none is attached
	 * @param maxSupportedSdk highest Android API level this MobSF supports for dynamic
	 * analysis (MobSF 4.5.4 reports 30, i.e. Android 11)
	 */
	public record DeviceStatus(boolean deviceAvailable, String mobsfUrl, String identifier, String androidVersion,
			Integer androidSdk, Integer maxSupportedSdk, List<InstalledApp> installedApps, String error,
			List<String> nextSteps) {
	}

	/**
	 * One app MobSF has on the device. MobSF returns these as objects keyed in upper case
	 * ({@code PACKAGE_NAME}, {@code APP_NAME}, ...), so they are unpacked here rather than
	 * handed to the caller as JSON embedded in a string.
	 *
	 * @param hash the MD5 to pass to start_dynamic, when this app was scanned by this MobSF
	 * @param dynamicReportExists a dynamic report is already stored for this app
	 */
	public record InstalledApp(String packageName, String appName, String versionName, String hash,
			boolean dynamicReportExists) {
	}

	public record DynamicRunResult(String hash, String status, String message, String nextStep) {
	}

	/**
	 * @param lineCount lines seen in the stream, which is more than {@code lines} holds
	 * when the log was longer than the limit
	 * @param truncated the oldest lines were dropped to honour the limit
	 * @param deadlineReached the stream was still producing when time ran out; MobSF tails
	 * the device forever, so this is the normal outcome on a live device
	 */
	public record LogcatResult(String packageName, int lineCount, boolean truncated, boolean deadlineReached,
			List<String> lines) {
	}

	public record FridaScripts(String device, List<String> scripts) {
	}

	@McpTool(name = "device_status",
			description = "Check whether MobSF has an Android device or emulator attached for dynamic analysis. "
					+ "Call this before any other dynamic tool; when no device is connected it lists exactly what to "
					+ "set up.",
			annotations = @McpTool.McpAnnotations(title = "Device status", readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	public DeviceStatus deviceStatus() {
		JsonNode response;
		try {
			response = client.dynamicApps();
		}
		catch (MobsfException ex) {
			return new DeviceStatus(false, properties.url(), null, null, null, null, List.of(), ex.getMessage(),
					setupSteps());
		}
		// MobSF answers 200 even with no device; 'identifier' is null when none is attached
		String identifier = response.path("identifier").isNull() ? null
				: response.path("identifier").asString(null);
		Integer sdk = response.path("android_sdk").isNumber() ? response.path("android_sdk").asInt() : null;
		Integer maxSdk = response.path("android_supported").isNumber() ? response.path("android_supported").asInt()
				: null;
		List<InstalledApp> apps = new ArrayList<>();
		for (JsonNode app : response.path("apps")) {
			if (app.isTextual()) {
				apps.add(new InstalledApp(app.asString(""), null, null, null, false));
				continue;
			}
			apps.add(new InstalledApp(Json.text(app, "PACKAGE_NAME"), Json.text(app, "APP_NAME"),
					Json.text(app, "VERSION_NAME"), Json.text(app, "MD5"),
					app.path("DYNAMIC_REPORT_EXISTS").asBoolean(false)));
		}

		if (identifier == null) {
			return new DeviceStatus(false, properties.url(), null, null, null, maxSdk, List.copyOf(apps),
					"MobSF has no Android device attached (identifier is null).", setupSteps());
		}
		List<String> next = new ArrayList<>();
		if (sdk != null && maxSdk != null && sdk > maxSdk) {
			next.add("This device runs API " + sdk + ", but this MobSF supports up to API " + maxSdk
					+ ". Dynamic analysis may fail; use an older image.");
		}
		next.add("If this device is new, run mobsfy_device once, then start_dynamic with an app hash.");
		return new DeviceStatus(true, properties.url(), identifier,
				response.path("android_version").asString(null), sdk, maxSdk, List.copyOf(apps), null,
				List.copyOf(next));
	}

	private static List<String> setupSteps() {
		return List.of("Start a rooted Android emulator or device: a Genymotion image, or an Android Studio AVD "
				+ "started with -writable-system (not a Google Play image).",
				"Connect it: 'adb connect <ip>:5555' (for a USB phone run 'adb tcpip 5555' first).",
				"Point MobSF at it: set MOBSF_ANALYZER_IDENTIFIER=host.docker.internal:5555 on the MobSF container "
						+ "(or <ip>:5555) and restart it.",
				"Call mobsfy_device with the same identifier to install the Frida server and MobSF CA.",
				"Then call device_status again.");
	}

	@McpTool(name = "mobsfy_device",
			description = "Prepare a connected Android device for dynamic analysis: MobSF installs its Frida server, "
					+ "root CA and proxy settings. Run once per device, after connecting it with adb.",
			annotations = @McpTool.McpAnnotations(title = "Prepare device (MobSFy)", readOnlyHint = false,
					destructiveHint = false, idempotentHint = true, openWorldHint = true))
	public DynamicRunResult mobsfyDevice(
			@McpToolParam(description = "Device identifier, e.g. host.docker.internal:5555 or emulator-5554") String identifier) {
		if (identifier == null || identifier.isBlank()) {
			throw new IllegalArgumentException(
					"'identifier' is required, e.g. host.docker.internal:5555. Run 'adb devices' to see it.");
		}
		JsonNode response = client.mobsfy(identifier.trim());
		// MobSF logs "MobSFying Completed!" even when a step inside it failed. The one that
		// fails most often is the frida-server download: MobSF gates it behind a
		// reachability probe of google.com and baidu.com, so on a network that blocks those
		// but allows GitHub it never tries the download that would have worked, and
		// /system/fd_server is never pushed. Every later Frida call then fails, also
		// reporting ok. See docs/dynamic-analysis-setup.md.
		return new DynamicRunResult(null, status(response), message(response),
				"MobSF reports success even when a step inside it failed, most often the frida-server "
						+ "download. Before relying on Frida, check 'docker logs mobsf' for 'Cannot download "
						+ "frida-server binary'. Then call device_status, then start_dynamic with an app hash.");
	}

	@McpTool(name = "start_dynamic",
			description = "Install and launch a scanned app on the connected device and begin dynamic analysis. "
					+ "The app must already be uploaded and statically scanned (use its hash). Can take minutes.",
			annotations = @McpTool.McpAnnotations(title = "Start dynamic analysis", readOnlyHint = false,
					destructiveHint = false, idempotentHint = false, openWorldHint = true))
	public DynamicRunResult startDynamic(@McpToolParam(description = "Hash of the uploaded app") String hash,
			@McpToolParam(description = "Reinstall the app if it is already present (default false)", required = false) Boolean reinstall,
			@McpToolParam(description = "Install the app; set false if it is already installed (default true)", required = false) Boolean install) {
		requireHash(hash);
		JsonNode response = client.startDynamic(hash, Boolean.TRUE.equals(reinstall), install == null || install);
		return new DynamicRunResult(hash, status(response), message(response),
				"Instrument first (frida_instrument), then exercise the app (test_activities, start_activity): the "
						+ "activity tester restarts the process, so Frida must attach before it. Then call stop_dynamic and "
						+ "get_dynamic_report.");
	}

	@McpTool(name = "stop_dynamic",
			description = "Stop dynamic analysis: MobSF collects logs, screenshots and app data from the device.",
			annotations = @McpTool.McpAnnotations(title = "Stop dynamic analysis", readOnlyHint = false,
					destructiveHint = false, idempotentHint = true, openWorldHint = true))
	public DynamicRunResult stopDynamic(@McpToolParam(description = "Hash of the app being analysed") String hash) {
		requireHash(hash);
		JsonNode response = client.stopDynamic(hash);
		return new DynamicRunResult(hash, status(response), message(response),
				"Call get_dynamic_report, or export_report with type=dynamic or combined.");
	}

	@McpTool(name = "get_dynamic_report",
			description = "MobSF's dynamic analysis report for an app: TLS tests, captured API calls, network traffic, "
					+ "files written and more. Run stop_dynamic first.",
			annotations = @McpTool.McpAnnotations(title = "Get dynamic report", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public JsonNode getDynamicReport(@McpToolParam(description = "Hash of the analysed app") String hash) {
		requireHash(hash);
		return client.dynamicReport(hash);
	}

	@McpTool(name = "run_tls_tests",
			description = "Run MobSF's TLS/SSL tests against the running app: cleartext traffic, certificate "
					+ "validation and certificate pinning.",
			annotations = @McpTool.McpAnnotations(title = "TLS tests", readOnlyHint = false, destructiveHint = false,
					idempotentHint = true, openWorldHint = true))
	public JsonNode runTlsTests(@McpToolParam(description = "Hash of the running app") String hash) {
		requireHash(hash);
		return client.tlsTests(hash);
	}

	@McpTool(name = "test_activities",
			description = "Launch the app's activities one by one and capture a screenshot of each. Set "
					+ "exportedOnly=true to test only exported activities, which is where access-control bugs show up.",
			annotations = @McpTool.McpAnnotations(title = "Activity tester", readOnlyHint = false,
					destructiveHint = false, idempotentHint = true, openWorldHint = true))
	public JsonNode testActivities(@McpToolParam(description = "Hash of the running app") String hash,
			@McpToolParam(description = "Only exported activities (default false)", required = false) Boolean exportedOnly) {
		requireHash(hash);
		return client.activityTester(hash, Boolean.TRUE.equals(exportedOnly) ? "exported" : "activity");
	}

	@McpTool(name = "start_activity",
			description = "Launch one specific activity of the running app, e.g. to reach a screen behind a login.",
			annotations = @McpTool.McpAnnotations(title = "Start activity", readOnlyHint = false,
					destructiveHint = false, idempotentHint = false, openWorldHint = true))
	public JsonNode startActivity(@McpToolParam(description = "Hash of the running app") String hash,
			@McpToolParam(description = "Activity name, e.g. .MainActivity") String activity) {
		requireHash(hash);
		if (activity == null || activity.isBlank()) {
			throw new IllegalArgumentException("'activity' is required; get_report section 'files' lists activities.");
		}
		return client.startActivity(hash, activity.trim());
	}

	@McpTool(name = "get_logcat",
			description = "Device log (logcat) for one package, newest lines last. Useful for spotting secrets or "
					+ "tokens the app logs at runtime. Call it while a dynamic session is running: MobSF attaches "
					+ "adb for the session, so after stop_dynamic there is no device to read from.",
			annotations = @McpTool.McpAnnotations(title = "Get logcat", readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	public LogcatResult getLogcat(@McpToolParam(description = "Package name, e.g. com.example.app") String packageName,
			@McpToolParam(description = "Maximum lines to return, newest kept (default 200)", required = false) Integer maxLines,
			@McpToolParam(description = "Seconds to spend draining the log stream (default 15, max 120)",
					required = false) Integer timeoutSeconds) {
		if (packageName == null || packageName.isBlank()) {
			throw new IllegalArgumentException("'packageName' is required; get it from get_scorecard or get_report.");
		}
		int limit = maxLines == null || maxLines < 1 ? 200 : Math.min(maxLines, 5000);
		int seconds = timeoutSeconds == null || timeoutSeconds < 1 ? 15 : Math.min(timeoutSeconds, 120);
		MobsfClient.Logcat logcat = client.logcat(packageName.trim(), limit, Duration.ofSeconds(seconds));
		if (logcat.waitingForDevice()) {
			throw new IllegalStateException("MobSF has no device attached, so logcat returned '- waiting for device -'. "
					+ "Logcat reads the device MobSF connects for a dynamic session: call start_dynamic first, read the "
					+ "log while the app is running, and call get_logcat before stop_dynamic.");
		}
		return new LogcatResult(packageName, logcat.totalLines(), logcat.truncated(), logcat.deadlineReached(),
				logcat.lines());
	}

	@McpTool(name = "frida_instrument",
			description = "Run Frida against the live app and hold the hooks in place while it is exercised. "
					+ "defaultHooks and auxiliaryHooks are MobSF's built-in hook sets (e.g. api_monitor, "
					+ "ssl_pinning_bypass, root_bypass, debugger_check_bypass); script is optional custom Frida "
					+ "JavaScript. This call blocks for durationSeconds: exercise the app from another client in "
					+ "the meantime, or call test_activities afterwards and instrument again. It reports how much "
					+ "the hooks actually captured, so an empty result means Frida did not attach.",
			annotations = @McpTool.McpAnnotations(title = "Frida instrument", readOnlyHint = false,
					destructiveHint = false, idempotentHint = false, openWorldHint = true))
	public FridaInstrumentResult fridaInstrument(@McpToolParam(description = "Hash of the running app") String hash,
			@McpToolParam(description = "Built-in hooks, comma separated, e.g. api_monitor,ssl_pinning_bypass", required = false) String defaultHooks,
			@McpToolParam(description = "Auxiliary hooks, comma separated", required = false) String auxiliaryHooks,
			@McpToolParam(description = "Custom Frida JavaScript to run", required = false) String script,
			@McpToolParam(description = "Class name to trace, for class-tracing hooks", required = false) String className,
			@McpToolParam(description = "Seconds to hold the hooks live while the app is exercised "
					+ "(default 20, max 300)", required = false) Integer durationSeconds) {
		requireHash(hash);
		int seconds = durationSeconds == null || durationSeconds < 1 ? 20 : Math.min(durationSeconds, 300);
		// MobSF's endpoint never answers while the hooks are live, so the client holds the
		// request open for this long and then ends it. See MobsfClient#fridaInstrument.
		client.fridaInstrument(hash, orEmpty(defaultHooks), orEmpty(auxiliaryHooks), orEmpty(script), className,
				Duration.ofSeconds(seconds));

		// MobSF reports nothing useful about whether Frida attached, so the hooks' own log is
		// the only honest signal. It is written as the session tears down, which happens
		// after the request is cancelled, so give it a moment to appear.
		//
		// The captured call count is deliberately not reported. MobSF keeps appending to the
		// API monitor for tens of seconds after the session ends -- a reading taken when this
		// call returns was a small fraction of the eventual total in every run here -- so any
		// number would be wrong. frida_api_monitor reads the real total whenever the caller
		// asks for it.
		List<String> logLines = awaitHookOutput(hash);
		return new FridaInstrumentResult(hash, seconds, logLines.size(), !logLines.isEmpty(),
				logLines.isEmpty() ? "No hook wrote anything. Frida did not attach: check that the app is running "
						+ "and frontmost, that frida-server is up on the device, and docs/dynamic-analysis-setup.md "
						+ "for the three MobSF problems that stop it." : null,
				logLines.isEmpty() ? null
						: "Call frida_api_monitor for the calls the hooks recorded; MobSF keeps writing to it for "
								+ "a while after the session ends, so a later call sees more.");
	}

	/**
	 * MobSF flushes the hook log as the session ends, which happens after the request is
	 * cancelled, so poll briefly rather than reporting a premature zero.
	 */
	private List<String> awaitHookOutput(String hash) {
		for (int attempt = 0; attempt < HOOK_OUTPUT_ATTEMPTS; attempt++) {
			List<String> lines = fridaLogLines(hash);
			if (!lines.isEmpty()) {
				return lines;
			}
			try {
				Thread.sleep(HOOK_OUTPUT_POLL.toMillis());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return List.of();
			}
		}
		return List.of();
	}

	private static final int HOOK_OUTPUT_ATTEMPTS = 15;

	private static final Duration HOOK_OUTPUT_POLL = Duration.ofSeconds(1);

	private List<String> fridaLogLines(String hash) {
		try {
			JsonNode response = client.fridaLogs(hash);
			String message = message(response);
			if (!"ok".equalsIgnoreCase(status(response)) || message == null || message.isBlank()) {
				return List.of();
			}
			return List.of(message.split("\r?\n"));
		}
		catch (MobsfException ex) {
			return List.of();
		}
	}

	/**
	 * @param hooksConfirmed a hook wrote output, which is the only evidence MobSF offers
	 * that Frida attached at all
	 */
	public record FridaInstrumentResult(String hash, int heldForSeconds, int logLines, boolean hooksConfirmed,
			String problem, String nextStep) {
	}

	@McpTool(name = "frida_api_monitor",
			description = "API calls Frida captured from the running app: crypto, file, network and IPC use.",
			annotations = @McpTool.McpAnnotations(title = "Frida API monitor", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public JsonNode fridaApiMonitor(@McpToolParam(description = "Hash of the running app") String hash) {
		requireHash(hash);
		return client.fridaApiMonitor(hash);
	}

	@McpTool(name = "frida_logs", description = "Output written by the Frida scripts currently running.",
			annotations = @McpTool.McpAnnotations(title = "Frida logs", readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	public JsonNode fridaLogs(@McpToolParam(description = "Hash of the running app") String hash) {
		requireHash(hash);
		JsonNode response = client.fridaLogs(hash);
		// MobSF says only "Data does not exist." when no hook has written anything, which
		// gives an agent nothing to act on. The causes are few and worth naming.
		if ("failed".equalsIgnoreCase(status(response))) {
			throw new IllegalStateException("MobSF has no Frida output for " + hash + " (\"" + message(response)
					+ "\"). Either no hook has run yet, or Frida never attached: frida_instrument reports success "
					+ "regardless. Check that a dynamic session is running (start_dynamic), that frida-server "
					+ "started on the device, and MobSF's own log for 'ServerNotRunningError'.");
		}
		return response;
	}

	@McpTool(name = "list_frida_scripts",
			description = "MobSF's built-in Frida scripts, which can be passed to frida_instrument as auxiliary hooks.",
			annotations = @McpTool.McpAnnotations(title = "List Frida scripts", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public FridaScripts listFridaScripts(
			@McpToolParam(description = "android or ios (default android)", required = false) String device) {
		String target = device == null || device.isBlank() ? "android" : device.trim().toLowerCase(Locale.ROOT);
		JsonNode response = client.listFridaScripts(target);
		List<String> scripts = new ArrayList<>();
		for (JsonNode script : response.path("files")) {
			scripts.add(script.asString(""));
		}
		return new FridaScripts(target, List.copyOf(scripts));
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}

	private static String status(JsonNode response) {
		return response.path("status").asString(response.has("error") ? "failed" : "ok");
	}

	private static String message(JsonNode response) {
		if (response.hasNonNull("message")) {
			return response.path("message").asString("");
		}
		return response.hasNonNull("error") ? response.path("error").asString("") : null;
	}

	private static void requireHash(String hash) {
		if (hash == null || hash.isBlank()) {
			throw new IllegalArgumentException("'hash' is required; get it from upload_app or list_scans.");
		}
	}

}
