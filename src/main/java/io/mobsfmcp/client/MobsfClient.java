// SPDX-License-Identifier: MIT
package io.mobsfmcp.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.mobsfmcp.config.MobsfProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin wrapper around the MobSF REST API ({@code /api/v1/*}). Every call is
 * authenticated with the {@code X-Mobsf-Api-Key} header, and every failure is mapped to
 * a {@link MobsfException} with an agent-readable message.
 */
@Component
public class MobsfClient {

	static final String API_KEY_HEADER = "X-Mobsf-Api-Key";

	private final MobsfProperties properties;

	private final RestClient restClient;

	/** Separate client for {@code /api/v1/scan}, which blocks for the whole scan. */
	private final RestClient scanClient;

	/** Used for the hand-built multipart upload (see {@link #upload}). */
	private final HttpClient uploadHttpClient;

	public MobsfClient(MobsfProperties properties, RestClient.Builder builder) {
		this.properties = properties;
		this.restClient = client(properties, builder, properties.readTimeout());
		this.scanClient = client(properties, builder, properties.scanTimeout());
		this.uploadHttpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(properties.connectTimeout())
			.build();
	}

	private static RestClient client(MobsfProperties properties, RestClient.Builder builder, Duration readTimeout) {
		// HTTP/1.1: MobSF runs behind gunicorn, and HTTP/2 upgrade attempts break multipart uploads.
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(properties.connectTimeout())
			.build());
		requestFactory.setReadTimeout(readTimeout);
		return builder.clone()
			.baseUrl(stripTrailingSlash(properties.url()))
			.requestFactory(requestFactory)
			.build();
	}

	public String baseUrl() {
		return properties.url();
	}

	/** {@code GET /api/v1/scans}: paginated list of recent scans. */
	public JsonNode recentScans(int page, int pageSize) {
		return call("list scans", () -> restClient.get()
			.uri(uri -> uri.path("/api/v1/scans").queryParam("page", page).queryParam("page_size", pageSize).build())
			.header(API_KEY_HEADER, requireApiKey())
			.retrieve()
			.body(JsonNode.class));
	}

	/**
	 * {@code POST /api/v1/upload}: multipart upload, returns {@code hash} and
	 * {@code scan_type}.
	 * <p>
	 * The multipart body is assembled by hand so the request carries an exact
	 * {@code Content-Length}: Django (and therefore MobSF) discards chunked multipart
	 * uploads with "file: This field is required", which is what a streamed body produces.
	 */
	public JsonNode upload(Resource file, String fileName) {
		String boundary = "mobsfmcp" + UUID.randomUUID().toString().replace("-", "");
		byte[] preamble = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
				+ "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
		byte[] epilogue = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
		return call("upload " + fileName, () -> {
			try {
				// concat reports the summed length, so the JDK client sets Content-Length itself
				HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
						HttpRequest.BodyPublishers.ofByteArray(preamble), filePublisher(file),
						HttpRequest.BodyPublishers.ofByteArray(epilogue));
				HttpRequest request = HttpRequest.newBuilder(URI.create(stripTrailingSlash(properties.url()) + "/api/v1/upload"))
					.version(HttpClient.Version.HTTP_1_1)
					.timeout(properties.readTimeout())
					.header(API_KEY_HEADER, requireApiKey())
					.header("Content-Type", "multipart/form-data; boundary=" + boundary)
					.POST(body)
					.build();
				HttpResponse<String> response = uploadHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() / 100 != 2) {
					throw fromStatus("upload " + fileName, HttpStatusCode.valueOf(response.statusCode()),
							response.body());
				}
				return JsonMapper.builder().build().readTree(response.body());
			}
			catch (IOException ex) {
				throw new MobsfException(MobsfException.Kind.UNREACHABLE,
						"Cannot reach MobSF at " + properties.url() + " to upload " + fileName + " ("
								+ ex.getClass().getSimpleName() + ": " + ex.getMessage()
								+ "). Check MOBSF_URL and that MobSF is running.", ex);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new MobsfException(MobsfException.Kind.UNREACHABLE, "Upload of " + fileName + " was interrupted.");
			}
		});
	}

	private static HttpRequest.BodyPublisher filePublisher(Resource file) throws IOException {
		if (file.isFile()) {
			return HttpRequest.BodyPublishers.ofFile(file.getFile().toPath());
		}
		try (InputStream in = file.getInputStream()) {
			return HttpRequest.BodyPublishers.ofByteArray(in.readAllBytes());
		}
	}

	/** {@code POST /api/v1/scan}: runs the static scan and returns the full report. Can take minutes. */
	public JsonNode scan(String hash, boolean rescan) {
		return call("scan " + hash, () -> scanClient.post()
			.uri("/api/v1/scan")
			.header(API_KEY_HEADER, requireApiKey())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form("hash", hash, "re_scan", rescan ? "1" : "0"))
			.retrieve()
			.body(JsonNode.class));
	}

	/** {@code POST /api/v1/scan_logs}: progress of a running or finished scan. */
	public JsonNode scanLogs(String hash) {
		return postForm("read scan logs for " + hash, "/api/v1/scan_logs", form("hash", hash));
	}

	/** {@code POST /api/v1/report_json}: the full static report. */
	public JsonNode reportJson(String hash) {
		return postForm("fetch the report for " + hash, "/api/v1/report_json", form("hash", hash));
	}

	/** {@code POST /api/v1/scorecard}: the app security scorecard (score, grade, finding counts). */
	public JsonNode scorecard(String hash) {
		return postForm("fetch the scorecard for " + hash, "/api/v1/scorecard", form("hash", hash));
	}

	/** {@code POST /api/v1/delete_scan}: removes a scan and its files from MobSF. */
	public JsonNode deleteScan(String hash) {
		return postForm("delete scan " + hash, "/api/v1/delete_scan", form("hash", hash));
	}

	/** {@code POST /api/v1/search}: finds a scan by hash, file name or package name. */
	public JsonNode search(String query) {
		return postForm("search for '" + query + "'", "/api/v1/search", form("query", query));
	}

	/** {@code POST /api/v1/download_pdf}: the PDF report as bytes. */
	public byte[] downloadPdf(String hash) {
		return call("download the PDF report for " + hash,
				() -> restClient.post()
					.uri("/api/v1/download_pdf")
					.header(API_KEY_HEADER, requireApiKey())
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.accept(MediaType.APPLICATION_PDF, MediaType.ALL)
					.body(form("hash", hash))
					.retrieve()
					.body(byte[].class));
	}

	// --- Findings: suppression and source ---

	/**
	 * {@code POST /api/v1/suppress_by_rule}: hides a rule's findings for this app.
	 * <p>
	 * MobSF stores suppressions per <em>package name</em>, not per scan, so this also
	 * applies to later scans of the same app. {@code type} is {@code code} or
	 * {@code manifest}.
	 */
	public JsonNode suppressByRule(String hash, String rule, String type) {
		return postForm("suppress rule " + rule, "/api/v1/suppress_by_rule",
				form("hash", hash, "rule", rule, "type", type));
	}

	/** {@code POST /api/v1/suppress_by_files}: hides a code rule's findings in the files it matched. */
	public JsonNode suppressByFiles(String hash, String rule) {
		return postForm("suppress rule " + rule + " by file", "/api/v1/suppress_by_files",
				form("hash", hash, "rule", rule));
	}

	/** {@code POST /api/v1/list_suppressions}: the suppressions in force for this app. */
	public JsonNode listSuppressions(String hash) {
		return postForm("list suppressions for " + hash, "/api/v1/list_suppressions", form("hash", hash));
	}

	/** {@code POST /api/v1/delete_suppression}: {@code kind} is {@code rule} or {@code file}. */
	public JsonNode deleteSuppression(String hash, String rule, String type, String kind) {
		return postForm("remove the suppression of " + rule, "/api/v1/delete_suppression",
				form("hash", hash, "rule", rule, "type", type, "kind", kind));
	}

	/**
	 * {@code POST /api/v1/view_source}: the source of one file from a scan.
	 * {@code type} is one of eclipse, studio, apk, java, smali or xml for Android;
	 * anything else is treated as iOS.
	 */
	public JsonNode viewSource(String hash, String file, String type) {
		return postForm("read " + file, "/api/v1/view_source", form("hash", hash, "file", file, "type", type));
	}

	// --- Dynamic analysis (needs an Android device or emulator attached to MobSF) ---

	/** {@code GET /api/v1/dynamic/get_apps}: apps available on the connected device (GET, unlike the rest). */
	public JsonNode dynamicApps() {
		return call("list apps on the device", () -> restClient.get()
			.uri("/api/v1/dynamic/get_apps")
			.header(API_KEY_HEADER, requireApiKey())
			.retrieve()
			.body(JsonNode.class));
	}

	/** {@code POST /api/v1/android/mobsfy}: prepares a device (Frida server, CA, proxy). */
	public JsonNode mobsfy(String identifier) {
		return postSlowForm("prepare device " + identifier, "/api/v1/android/mobsfy", form("identifier", identifier));
	}

	/** {@code POST /api/v1/dynamic/start_analysis}: installs and launches the app on the device. */
	public JsonNode startDynamic(String hash, boolean reinstall, boolean install) {
		return postSlowForm("start dynamic analysis of " + hash, "/api/v1/dynamic/start_analysis",
				form("hash", hash, "re_install", reinstall ? "1" : "0", "install", install ? "1" : "0"));
	}

	/** {@code POST /api/v1/dynamic/stop_analysis}: collects logs and stops the run. */
	public JsonNode stopDynamic(String hash) {
		return postSlowForm("stop dynamic analysis of " + hash, "/api/v1/dynamic/stop_analysis", form("hash", hash));
	}

	/** {@code POST /api/v1/dynamic/report_json}: the dynamic analysis report. */
	public JsonNode dynamicReport(String hash) {
		return postForm("fetch the dynamic report for " + hash, "/api/v1/dynamic/report_json", form("hash", hash));
	}

	/**
	 * {@code POST /api/v1/android/logcat}: the device log for one package.
	 * <p>
	 * This is not a JSON endpoint, which is why it cannot go through
	 * {@link #postForm}. MobSF answers {@code text/event-stream} and runs
	 * {@code adb logcat} without {@code -d}, so it replays the whole ring buffer and then
	 * tails the device forever: the response never completes on its own. Each event is
	 * {@code data:} followed by Python's {@code repr()} of a bytes object, for example
	 * {@code data:b'11-26 02:37:30.831 0 0 I ...
'}.
	 * <p>
	 * The stream is therefore drained under a deadline on a separate thread and the
	 * newest {@code maxLines} are kept in a ring buffer: the buffer is replayed oldest
	 * first, so keeping the first lines read would return the oldest log, not the newest.
	 * The connection is closed once the deadline passes, which is what unblocks the read.
	 */
	public Logcat logcat(String packageName, int maxLines, Duration deadline) {
		String body = "package=" + URLEncoder.encode(packageName, StandardCharsets.UTF_8);
		return call("read logcat for " + packageName, () -> {
			HttpRequest request = HttpRequest
				.newBuilder(URI.create(stripTrailingSlash(properties.url()) + "/api/v1/android/logcat"))
				.version(HttpClient.Version.HTTP_1_1)
				.timeout(properties.readTimeout())
				.header(API_KEY_HEADER, requireApiKey())
				.header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE)
				.header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
			try {
				HttpResponse<InputStream> response = uploadHttpClient.send(request,
						HttpResponse.BodyHandlers.ofInputStream());
				if (response.statusCode() / 100 != 2) {
					try (InputStream in = response.body()) {
						throw fromStatus("read logcat for " + packageName,
								HttpStatusCode.valueOf(response.statusCode()),
								new String(in.readAllBytes(), StandardCharsets.UTF_8));
					}
				}
				return drainLogcat(response.body(), maxLines, deadline);
			}
			catch (IOException ex) {
				throw new MobsfException(MobsfException.Kind.UNREACHABLE,
						"Cannot reach MobSF at " + properties.url() + " to read logcat ("
								+ ex.getClass().getSimpleName() + ": " + ex.getMessage()
								+ "). Check MOBSF_URL and that MobSF is running.", ex);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new MobsfException(MobsfException.Kind.UNREACHABLE,
						"Reading logcat for " + packageName + " was interrupted.");
			}
		});
	}

	/** What a bounded read of the logcat stream collected. */
	public record Logcat(List<String> lines, int totalLines, boolean truncated, boolean deadlineReached,
			boolean waitingForDevice) {
	}

	/** Marker MobSF streams when its adb has no device attached. */
	private static final String WAITING_FOR_DEVICE = "- waiting for device -";

	private static Logcat drainLogcat(InputStream stream, int maxLines, Duration deadline) {
		Deque<String> tail = new ArrayDeque<>(maxLines);
		AtomicInteger total = new AtomicInteger();
		AtomicBoolean waiting = new AtomicBoolean();
		Thread reader = Thread.ofVirtual().start(() -> {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String raw;
				while ((raw = in.readLine()) != null) {
					String line = decodeEvent(raw);
					if (line == null) {
						continue;
					}
					if (line.contains(WAITING_FOR_DEVICE)) {
						waiting.set(true);
					}
					total.incrementAndGet();
					synchronized (tail) {
						if (tail.size() == maxLines) {
							tail.removeFirst();
						}
						tail.addLast(line);
					}
				}
			}
			catch (IOException | RuntimeException ex) {
				// Expected: the stream is closed from under the reader once the deadline passes.
			}
		});
		boolean finished;
		try {
			finished = reader.join(deadline);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			finished = false;
		}
		if (!finished) {
			closeQuietly(stream);
			reader.interrupt();
		}
		synchronized (tail) {
			return new Logcat(List.copyOf(tail), total.get(), total.get() > tail.size(), !finished, waiting.get());
		}
	}

	private static void closeQuietly(InputStream stream) {
		try {
			stream.close();
		}
		catch (IOException ignored) {
			// nothing useful to do; the read is being abandoned on purpose
		}
	}

	/**
	 * Turns one SSE line into a log line. MobSF writes {@code data:} followed by Python's
	 * {@code repr()} of a bytes object, so the quotes and the {@code b} prefix are
	 * stripped and the escapes it emits are undone. Blank lines and comments yield
	 * {@code null}.
	 */
	static String decodeEvent(String raw) {
		if (!raw.startsWith("data:")) {
			return null;
		}
		String value = raw.substring(5).strip();
		if ((value.startsWith("b'") && value.endsWith("'") || value.startsWith("b\"") && value.endsWith("\""))
				&& value.length() >= 3) {
			value = value.substring(2, value.length() - 1);
		}
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c != '\\' || i + 1 >= value.length()) {
				out.append(c);
				continue;
			}
			char next = value.charAt(++i);
			switch (next) {
				case 'n' -> out.append('\n');
				case 't' -> out.append('\t');
				case 'r' -> { /* MobSF ends every event with one; a log line carries no CR */ }
				case 'x' -> {
					if (i + 2 < value.length()) {
						try {
							out.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
							i += 2;
						}
						catch (NumberFormatException ex) {
							out.append("\\x");
						}
					}
				}
				default -> out.append(next);
			}
		}
		return out.toString().stripTrailing().isEmpty() ? null : out.toString().stripTrailing();
	}

	/** {@code POST /api/v1/android/tls_tests}: TLS/SSL misconfiguration and pinning tests. */
	public JsonNode tlsTests(String hash) {
		return postSlowForm("run TLS tests for " + hash, "/api/v1/android/tls_tests", form("hash", hash));
	}

	/** {@code POST /api/v1/android/activity}: exercises activities; {@code test} is activity or exported. */
	public JsonNode activityTester(String hash, String test) {
		return postSlowForm("test " + test + " activities of " + hash, "/api/v1/android/activity",
				form("hash", hash, "test", test));
	}

	/** {@code POST /api/v1/android/start_activity}: launches one activity. */
	public JsonNode startActivity(String hash, String activity) {
		return postSlowForm("start activity " + activity, "/api/v1/android/start_activity",
				form("hash", hash, "activity", activity));
	}

	/** {@code POST /api/v1/frida/instrument}: runs Frida hooks and custom scripts against the app. */
	public JsonNode fridaInstrument(String hash, String defaultHooks, String auxiliaryHooks, String code,
			String className) {
		MultiValueMap<String, String> form = form("hash", hash, "default_hooks", defaultHooks, "auxiliary_hooks",
				auxiliaryHooks, "frida_code", code);
		form.add("class_name", className == null ? "" : className);
		return postSlowForm("instrument " + hash + " with Frida", "/api/v1/frida/instrument", form);
	}

	/** {@code POST /api/v1/frida/api_monitor}: API calls captured by Frida. */
	public JsonNode fridaApiMonitor(String hash) {
		return postForm("read the Frida API monitor for " + hash, "/api/v1/frida/api_monitor", form("hash", hash));
	}

	/** {@code POST /api/v1/frida/logs}: output of the running Frida scripts. */
	public JsonNode fridaLogs(String hash) {
		return postForm("read Frida logs for " + hash, "/api/v1/frida/logs", form("hash", hash));
	}

	/** {@code POST /api/v1/frida/list_scripts}: built-in Frida scripts; {@code device} is android or ios. */
	public JsonNode listFridaScripts(String device) {
		return postForm("list Frida scripts", "/api/v1/frida/list_scripts", form("device", device));
	}

	/** {@code POST /api/v1/frida/get_script}: source of one built-in Frida script. */
	public JsonNode fridaScript(String device, String scriptName) {
		MultiValueMap<String, String> form = form("device", device);
		form.add("scripts[]", scriptName);
		return postForm("read the Frida script " + scriptName, "/api/v1/frida/get_script", form);
	}

	/** {@code POST /api/v1/android/adb_command}: raw adb; only exposed when explicitly enabled. */
	public JsonNode adbCommand(String command) {
		return postSlowForm("run adb command", "/api/v1/android/adb_command", form("cmd", command));
	}

	private JsonNode postSlowForm(String action, String path, MultiValueMap<String, String> form) {
		return call(action, () -> scanClient.post()
			.uri(path)
			.header(API_KEY_HEADER, requireApiKey())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.retrieve()
			.body(JsonNode.class));
	}

	private JsonNode postForm(String action, String path, MultiValueMap<String, String> form) {
		return call(action, () -> restClient.post()
			.uri(path)
			.header(API_KEY_HEADER, requireApiKey())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.retrieve()
			.body(JsonNode.class));
	}

	private static MultiValueMap<String, String> form(String... keysAndValues) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			form.add(keysAndValues[i], keysAndValues[i + 1]);
		}
		return form;
	}

	private String requireApiKey() {
		if (!properties.hasApiKey()) {
			throw new MobsfException(MobsfException.Kind.NOT_CONFIGURED,
					"MOBSF_API_KEY is not set. Copy the REST API key from the MobSF web UI (API Docs page) "
							+ "or set MOBSF_API_KEY on the MobSF container, then pass the same value to mobsf-mcp.");
		}
		return properties.apiKey();
	}

	private <T> T call(String action, Supplier<T> request) {
		T body;
		try {
			body = request.get();
		}
		catch (MobsfException ex) {
			throw ex;
		}
		catch (RestClientResponseException ex) {
			throw fromStatus(action, ex.getStatusCode(), ex.getResponseBodyAsString());
		}
		catch (ResourceAccessException ex) {
			throw new MobsfException(MobsfException.Kind.UNREACHABLE, "Cannot reach MobSF at " + properties.url()
					+ " to " + action + " (" + rootMessage(ex) + "). Check MOBSF_URL and that MobSF is running. "
					+ "From inside Docker use http://host.docker.internal:8000 or the compose service name, not localhost.",
					ex);
		}
		catch (RestClientException ex) {
			throw new MobsfException(MobsfException.Kind.INVALID_RESPONSE,
					"Unexpected response from MobSF while trying to " + action + ": " + rootMessage(ex), ex);
		}
		if (body == null) {
			throw new MobsfException(MobsfException.Kind.INVALID_RESPONSE,
					"MobSF returned an empty response while trying to " + action + ".");
		}
		return body;
	}

	private MobsfException fromStatus(String action, HttpStatusCode status, String body) {
		String detail = abbreviate(body);
		return switch (status.value()) {
			case 401, 403 -> new MobsfException(MobsfException.Kind.UNAUTHORIZED,
					"MobSF rejected the API key (HTTP " + status.value() + ") while trying to " + action
							+ ". Check that MOBSF_API_KEY matches the key shown in the MobSF web UI.");
			case 404 -> new MobsfException(MobsfException.Kind.NOT_FOUND,
					"MobSF returned 404 while trying to " + action + ": " + detail);
			default -> status.is4xxClientError()
					? new MobsfException(MobsfException.Kind.BAD_REQUEST,
							"MobSF rejected the request to " + action + " (HTTP " + status.value() + "): " + detail)
					: new MobsfException(MobsfException.Kind.SERVER_ERROR,
							"MobSF failed to " + action + " (HTTP " + status.value() + "): " + detail);
		};
	}

	private static String rootMessage(Throwable ex) {
		Throwable root = ex;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String message = root.getMessage();
		return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
	}

	private static String abbreviate(String body) {
		if (body == null || body.isBlank()) {
			return "<empty body>";
		}
		String oneLine = body.replaceAll("\\s+", " ").trim();
		return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
	}

	private static String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

}
