// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.util.List;
import java.util.Locale;

import io.mobsfmcp.client.MobsfClient;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Raw adb access, registered only when {@code ALLOW_ADB_SHELL=true}. Arbitrary adb turns
 * the MCP server into remote shell access to the device, so it is off by default and
 * limited to read-only inspection commands even when enabled.
 */
@Component
@ConditionalOnProperty(name = "mobsf-mcp.allow-adb-shell", havingValue = "true")
public class AdbTools {

	/** Commands that only read state. Anything else is refused. */
	static final List<String> ALLOWED_PREFIXES = List.of("shell pm list packages", "shell dumpsys package",
			"shell ps", "shell getprop", "shell ls", "shell cat /proc", "shell settings get", "shell am stack list",
			"devices", "shell id", "shell whoami");

	private final MobsfClient client;

	public AdbTools(MobsfClient client) {
		this.client = client;
	}

	@McpTool(name = "adb_command",
			description = "Run a read-only adb command against the connected device (only enabled when the server is "
					+ "started with ALLOW_ADB_SHELL=true). Allowed: pm list packages, dumpsys package, ps, getprop, "
					+ "ls, cat /proc, settings get, am stack list, devices.",
			annotations = @McpTool.McpAnnotations(title = "adb command (read-only)", readOnlyHint = true,
					destructiveHint = false, idempotentHint = false, openWorldHint = true))
	public JsonNode adbCommand(@McpToolParam(description = "adb arguments, e.g. 'shell pm list packages'") String command) {
		if (command == null || command.isBlank()) {
			throw new IllegalArgumentException("'command' is required.");
		}
		String normalized = command.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
		if (normalized.contains("&&") || normalized.contains("|") || normalized.contains(";")
				|| normalized.contains("`") || normalized.contains("$(")) {
			throw new IllegalArgumentException("Command chaining is not allowed; run one adb command at a time.");
		}
		if (ALLOWED_PREFIXES.stream().noneMatch(normalized::startsWith)) {
			throw new IllegalArgumentException("'" + command + "' is not an allowed adb command. Allowed: "
					+ String.join(", ", ALLOWED_PREFIXES) + ".");
		}
		return client.adbCommand(command.trim());
	}

}
