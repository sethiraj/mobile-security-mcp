// SPDX-License-Identifier: MIT
package io.mobsfmcp.example.appium;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param serverUrl the Appium server, e.g. http://127.0.0.1:4723
 * @param deviceName the adb identifier Appium attaches to. It must be the same device
 * MobSF is driving, or the two halves end up watching different phones.
 */
@ConfigurationProperties(prefix = "appium")
public record AppiumProperties(String serverUrl, String deviceName, String platformVersion, Duration commandTimeout) {

	public AppiumProperties {
		serverUrl = (serverUrl == null || serverUrl.isBlank()) ? "http://127.0.0.1:4723" : serverUrl;
		deviceName = (deviceName == null || deviceName.isBlank()) ? "emulator-5554" : deviceName;
		commandTimeout = (commandTimeout == null) ? Duration.ofSeconds(60) : commandTimeout;
	}

}
