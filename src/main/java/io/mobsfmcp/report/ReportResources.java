// SPDX-License-Identifier: MIT
package io.mobsfmcp.report;

import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.report.model.ScanReport;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/**
 * MCP resources, so clients can subscribe to or link a report by URI instead of calling a
 * tool.
 */
@Component
public class ReportResources {

	private final MobsfClient client;

	private final StaticReportMapper staticMapper;

	private final tools.jackson.databind.json.JsonMapper jsonMapper = tools.jackson.databind.json.JsonMapper.builder()
		.build();

	public ReportResources(MobsfClient client, StaticReportMapper staticMapper) {
		this.client = client;
		this.staticMapper = staticMapper;
	}

	// Resource methods must return String (or the MCP content types), so both serialize to JSON text.

	@McpResource(uri = "mobsf://report/{hash}/static", name = "static-report",
			title = "Normalized static analysis report",
			description = "MobSF static analysis for one app, in the mobsf-mcp-report/v1 schema.",
			mimeType = "application/json")
	public String staticReport(String hash) {
		ScanReport report = staticMapper.map(hash, client.reportJson(hash));
		return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
	}

	@McpResource(uri = "mobsf://report/{hash}/raw", name = "raw-report", title = "MobSF report as MobSF returns it",
			description = "The unmodified MobSF report_json output for one app.", mimeType = "application/json")
	public String rawReport(String hash) {
		return client.reportJson(hash).toString();
	}

}
