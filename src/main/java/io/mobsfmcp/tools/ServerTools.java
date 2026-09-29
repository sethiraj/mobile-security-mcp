// SPDX-License-Identifier: MIT
package io.mobsfmcp.tools;

import java.util.ArrayList;
import java.util.List;

import io.mobsfmcp.client.MobsfClient;
import io.mobsfmcp.client.MobsfException;
import io.mobsfmcp.config.MobsfProperties;
import io.mobsfmcp.scans.ScanSummary;
import tools.jackson.databind.JsonNode;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Connectivity and scan-listing tools (M1). */
@Component
public class ServerTools {

	static final int MAX_PAGE_SIZE = 100;

	private final MobsfClient client;

	private final MobsfProperties properties;

	public ServerTools(MobsfClient client, MobsfProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	public record HealthStatus(boolean ok, String mobsfUrl, boolean apiKeyConfigured, String apiKey, Integer totalScans,
			String error, String errorKind) {
	}

	public record ScanPage(int page, int pageSize, int totalScans, int totalPages, List<ScanSummary> scans) {
	}

	@McpTool(name = "mobsf_health",
			description = "Check that MobSF is reachable and the API key is valid. Call this first; on failure the "
					+ "'error' field says exactly what to fix (MOBSF_URL, MOBSF_API_KEY or MobSF not running).",
			annotations = @McpTool.McpAnnotations(title = "MobSF health check", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public HealthStatus health() {
		try {
			JsonNode page = client.recentScans(1, 1);
			return new HealthStatus(true, properties.url(), true, properties.maskedApiKey(), page.path("count").asInt(0),
					null, null);
		}
		catch (MobsfException ex) {
			return new HealthStatus(false, properties.url(), properties.hasApiKey(), properties.maskedApiKey(), null,
					ex.getMessage(), ex.kind().name());
		}
	}

	@McpTool(name = "list_scans",
			description = "List apps already scanned by MobSF, newest first. Each entry's 'hash' (MD5) identifies the "
					+ "scan for the report tools.",
			annotations = @McpTool.McpAnnotations(title = "List MobSF scans", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false))
	public ScanPage listScans(@McpToolParam(description = "Page number, starting at 1 (default 1)", required = false) Integer page,
			@McpToolParam(description = "Scans per page, 1-" + MAX_PAGE_SIZE + " (default 10)", required = false) Integer pageSize) {
		int p = page == null || page < 1 ? 1 : page;
		int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, MAX_PAGE_SIZE);
		JsonNode body = client.recentScans(p, size);
		List<ScanSummary> scans = new ArrayList<>();
		for (JsonNode item : body.path("content")) {
			scans.add(ScanSummary.from(item));
		}
		return new ScanPage(p, size, body.path("count").asInt(scans.size()), body.path("num_pages").asInt(1), scans);
	}

}
