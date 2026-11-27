// SPDX-License-Identifier: MIT
package io.mobsfmcp.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A chat interface over two tool sets: mobsf-mcp for security analysis, and Appium for
 * driving the app on the same emulator. The model decides which to call.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApkSecurityChatApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApkSecurityChatApplication.class, args);
	}

}
