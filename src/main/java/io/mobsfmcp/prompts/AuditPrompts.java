// SPDX-License-Identifier: MIT
package io.mobsfmcp.prompts;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/** Ready-made workflows an MCP client can offer to its users. */
@Component
public class AuditPrompts {

	@McpPrompt(name = "full-apk-security-review",
			description = "Upload an app, run MobSF static analysis and summarise the security findings.")
	public McpSchema.GetPromptResult fullReview(
			@McpArg(name = "app", description = "Path (inside the server's upload directory) or https URL of the app",
					required = true) String app,
			@McpArg(name = "audience", description = "Who the summary is for: developer, security or management",
					required = false) String audience) {
		String readers = audience == null || audience.isBlank() ? "a developer" : audience;
		String instructions = """
				Review the mobile app %s with MobSF, using the MCP tools:

				1. Call mobsf_health. If it fails, report exactly what needs fixing and stop.
				2. Call upload_app (use 'path' for a local file, 'url' for https), then scan_app with the returned hash.
				   If scan_app reports status "running", poll scan_status until it completes.
				3. Call get_scorecard for the score and counts, then get_report for the sections that matter:
					 manifest, permissions, network, secrets, certificate, code.
				4. Write a summary for %s:
					 - the security score and grade, and what they mean
					 - every high severity finding: what it is, why it matters for this app, how to fix it
					 - exported components, dangerous permissions, cleartext traffic and hardcoded secrets
					 - trackers and third-party libraries worth noting
				5. End with a prioritised, actionable list of fixes.

				Report only what the tools actually returned, and say so when something could not be checked.
				"""
			.formatted(app, readers);
		return new McpSchema.GetPromptResult("MobSF security review of " + app,
				List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(instructions))));
	}

}
