// SPDX-License-Identifier: MIT
package io.mobsfmcp;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MobsfMcpApplication {

	public static void main(String[] args) {
		TransportMode.Resolution resolution;
		try {
			resolution = TransportMode.resolve(args, System.getenv());
		}
		catch (IllegalArgumentException ex) {
			System.err.println("mobsf-mcp: " + ex.getMessage());
			System.exit(2);
			return;
		}
		builder(resolution.mode()).run(resolution.remainingArgs());
	}

	static SpringApplicationBuilder builder(TransportMode mode) {
		boolean stdio = mode == TransportMode.STDIO;
		return new SpringApplicationBuilder(MobsfMcpApplication.class)
			.web(stdio ? WebApplicationType.NONE : WebApplicationType.SERVLET)
			.bannerMode(Banner.Mode.OFF)
			.properties("spring.ai.mcp.server.stdio=" + stdio, "mobsf-mcp.transport=" + mode.name().toLowerCase());
	}

}
