// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.nio.file.Path;
import java.time.Duration;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.mobsfmcp.MobsfStub;
import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.config.MobsfProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicAnalysisToolsTest {

	static final String HASH = MobsfStub.HASH_FOR_DYNAMIC;

	static WireMockServer mobsf;

	@BeforeAll
	static void start() {
		mobsf = MobsfStub.start();
		MobsfStub.stubDynamicAnalysis(mobsf, HASH);
	}

	@AfterAll
	static void stop() {
		mobsf.stop();
	}

	private static MobsfProperties properties(String url) {
		return new MobsfProperties(url, MobsfStub.API_KEY, Path.of("/data/apks"), Path.of("/data/reports"),
				Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(30), 500);
	}

	private DynamicAnalysisTools tools() {
		MobsfProperties properties = properties(mobsf.baseUrl());
		return new DynamicAnalysisTools(new MobsfClient(properties, RestClient.builder()), properties);
	}

	@Test
	void deviceStatusReportsTheAttachedDevice() {
		DynamicAnalysisTools.DeviceStatus status = tools().deviceStatus();

		assertThat(status.deviceAvailable()).isTrue();
		assertThat(status.identifier()).isEqualTo("host.docker.internal:5555");
		assertThat(status.androidSdk()).isEqualTo(30);
		assertThat(status.maxSupportedSdk()).isEqualTo(30);
		assertThat(status.installedPackages()).contains("jakhar.aseem.diva");
		assertThat(status.error()).isNull();
	}

	@Test
	void noDeviceIsDetectedFromTheNullIdentifierEvenThoughMobsfAnswers200() {
		WireMockServer bare = MobsfStub.start();
		bare.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/api/v1/dynamic/get_apps"))
			.willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson(
					"{\"apps\": [], \"identifier\": null, \"android_supported\": 30}")));
		try {
			MobsfProperties properties = properties(bare.baseUrl());
			var tools = new DynamicAnalysisTools(new MobsfClient(properties, RestClient.builder()), properties);

			DynamicAnalysisTools.DeviceStatus status = tools.deviceStatus();

			assertThat(status.deviceAvailable()).isFalse();
			assertThat(status.error()).contains("no Android device");
			assertThat(status.maxSupportedSdk()).isEqualTo(30);
			assertThat(status.nextSteps()).anySatisfy(step -> assertThat(step).contains("MOBSF_ANALYZER_IDENTIFIER"))
				.anySatisfy(step -> assertThat(step).contains("adb connect"));
		}
		finally {
			bare.stop();
		}
	}

	@Test
	void deviceStatusExplainsTheSetupWhenMobsfCannotBeReached() {
		MobsfProperties properties = properties("http://127.0.0.1:9");
		var offline = new DynamicAnalysisTools(new MobsfClient(properties, RestClient.builder()), properties);

		DynamicAnalysisTools.DeviceStatus status = offline.deviceStatus();

		assertThat(status.deviceAvailable()).isFalse();
		assertThat(status.error()).isNotBlank();
		assertThat(status.nextSteps()).isNotEmpty();
	}

	@Test
	void warnsWhenTheDeviceIsNewerThanMobsfSupports() {
		WireMockServer newer = MobsfStub.start();
		newer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/api/v1/dynamic/get_apps"))
			.willReturn(com.github.tomakehurst.wiremock.client.WireMock
				.okJson("{\"apps\": [], \"identifier\": \"emulator-5554\", \"android_version\": \"14\","
						+ " \"android_sdk\": 34, \"android_supported\": 30}")));
		try {
			MobsfProperties properties = properties(newer.baseUrl());
			var tools = new DynamicAnalysisTools(new MobsfClient(properties, RestClient.builder()), properties);

			assertThat(tools.deviceStatus().nextSteps()).anySatisfy(
					step -> assertThat(step).contains("API 34").contains("supports up to API 30"));
		}
		finally {
			newer.stop();
		}
	}

	@Test
	void mobsfyRequiresAnIdentifierAndReportsMobsfsAnswer() {
		assertThatThrownBy(() -> tools().mobsfyDevice(" ")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("identifier");

		DynamicAnalysisTools.DynamicRunResult result = tools().mobsfyDevice("host.docker.internal:5555");

		assertThat(result.status()).isEqualTo("ok");
		assertThat(result.message()).isEqualTo("Device configured");
		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/android/mobsfy"))
			.withRequestBody(containing("identifier=host.docker.internal")));
	}

	@Test
	void startAndStopSendTheFlagsMobsfExpects() {
		DynamicAnalysisTools.DynamicRunResult started = tools().startDynamic(HASH, true, null);

		assertThat(started.status()).isEqualTo("ok");
		assertThat(started.nextStep()).contains("stop_dynamic");
		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/dynamic/start_analysis"))
			.withRequestBody(containing("re_install=1"))
			.withRequestBody(containing("install=1")));

		assertThat(tools().stopDynamic(HASH).nextStep()).contains("export_report");
	}

	@Test
	void logcatDecodesTheEventStreamAndKeepsTheNewestLines() {
		DynamicAnalysisTools.LogcatResult logcat = tools().getLogcat("com.example.vuln", 2, 5);

		assertThat(logcat.lineCount()).isEqualTo(3);
		assertThat(logcat.truncated()).isTrue();
		assertThat(logcat.deadlineReached()).isFalse();
		// MobSF replays the buffer oldest first, so the newest lines are the last ones read
		assertThat(logcat.lines()).containsExactly("D/vuln: token abc123", "E/vuln: crypto failed");
	}

	@Test
	void logcatReturnsEverythingWhenTheLimitIsNotReached() {
		DynamicAnalysisTools.LogcatResult logcat = tools().getLogcat("com.example.vuln", 200, 5);

		assertThat(logcat.truncated()).isFalse();
		assertThat(logcat.lines()).hasSize(3).first().asString().isEqualTo("--------- beginning of main");
	}

	@Test
	void logcatWithoutADeviceSaysToStartASessionFirst() {
		assertThatThrownBy(() -> tools().getLogcat(MobsfStub.PACKAGE_WITHOUT_DEVICE, 10, 5))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("start_dynamic")
			.hasMessageContaining("before stop_dynamic");
	}

	@Test
	void activityTesterPicksExportedOrAllActivities() {
		tools().testActivities(HASH, true);
		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/android/activity"))
			.withRequestBody(containing("test=exported")));

		tools().testActivities(HASH, false);
		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/android/activity"))
			.withRequestBody(containing("test=activity")));
	}

	@Test
	void fridaInstrumentSendsEveryParameterMobsfRequires() {
		tools().fridaInstrument(HASH, "api_monitor", "", "console.log(1)", null);

		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/frida/instrument"))
			.withRequestBody(containing("default_hooks=api_monitor"))
			.withRequestBody(containing("auxiliary_hooks="))
			.withRequestBody(containing("frida_code=")));
	}

	@Test
	void fridaInstrumentDoesNotPresentMobsfsOkAsAttachment() {
		// MobSF returns ok whether or not Frida attached, so the result must not imply it did
		DynamicAnalysisTools.FridaInstrumentResult result = tools().fridaInstrument(HASH, "api_monitor", null, null,
				null);

		assertThat(result.status()).isEqualTo("ok");
		assertThat(result.attachmentConfirmed()).isFalse();
		assertThat(result.verifyWith()).contains("frida_logs");
	}

	@Test
	void fridaLogsExplainsMobsfsBareFailure() {
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/frida/logs"))
			.willReturn(okJson("{\"status\": \"failed\", \"message\": \"Data does not exist.\"}")));

		assertThatThrownBy(() -> tools().fridaLogs(HASH)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Data does not exist.")
			.hasMessageContaining("frida_instrument reports success regardless")
			.hasMessageContaining("ServerNotRunningError");
	}

	@Test
	void listsBuiltInFridaScripts() {
		DynamicAnalysisTools.FridaScripts scripts = tools().listFridaScripts(null);

		assertThat(scripts.device()).isEqualTo("android");
		assertThat(scripts.scripts()).contains("ssl_pinning_bypass.js");
		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/frida/list_scripts"))
			.withRequestBody(containing("device=android")));
	}

	@Test
	void dynamicReportIsReturnedAsMobsfSendsIt() {
		JsonNode report = tools().getDynamicReport(HASH);

		assertThat(report.path("package").asString()).isEqualTo("com.example.vuln");
		assertThat(report.path("tls_tests").has("tls_pinning_missing")).isTrue();
	}

	@Test
	void missingDeviceErrorsAreReportedWithMobsfsMessage() {
		mobsf.stubFor(post(urlPathEqualTo("/api/v1/android/tls_tests"))
			.withRequestBody(containing("hash=nodevice"))
			.willReturn(aResponse().withStatus(500).withBody("{\"error\":\"Device not connected\"}")));

		assertThatThrownBy(() -> tools().runTlsTests("nodevice")).hasMessageContaining("Device not connected");
	}

	@Test
	void adbToolAllowsOnlyReadOnlyCommands() {
		AdbTools adb = new AdbTools(new MobsfClient(properties(mobsf.baseUrl()), RestClient.builder()));

		assertThat(adb.adbCommand("shell pm list packages").path("status").asString()).isEqualTo("ok");
		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/android/adb_command"))
			.withRequestBody(containing("cmd=shell+pm+list+packages")));

		assertThatThrownBy(() -> adb.adbCommand("shell rm -rf /data")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not an allowed adb command");
		assertThatThrownBy(() -> adb.adbCommand("shell ps && shell rm -rf /data"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Command chaining");
	}

	@Test
	void apiMonitorIsPassedThrough() {
		assertThat(tools().fridaApiMonitor(HASH).path("data").get(0).path("class").asString())
			.isEqualTo("javax.crypto.Cipher");
		mobsf.verify(postRequestedFor(urlPathEqualTo("/api/v1/frida/api_monitor"))
			.withHeader("X-Mobsf-Api-Key", equalTo(MobsfStub.API_KEY)));
	}

}
