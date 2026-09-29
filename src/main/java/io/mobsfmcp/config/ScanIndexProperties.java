// SPDX-License-Identifier: MIT
package io.mobsfmcp.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the app index, bound from {@code mobsf-mcp.scan-index.*} (env:
 * {@code MCP_SCAN_INDEX_*}).
 *
 * @param cacheTtl how long a built index is reused; the index is also invalidated
 * explicitly whenever this server uploads, scans or deletes something
 * @param maxScans upper bound on rows pulled from MobSF, so a server with a huge history
 * cannot make a single tool call walk hundreds of pages
 * @param pageSize page size used when walking {@code /api/v1/scans}
 */
@ConfigurationProperties("mobsf-mcp.scan-index")
public record ScanIndexProperties(@DefaultValue("60s") Duration cacheTtl, @DefaultValue("2000") int maxScans,
		@DefaultValue("100") int pageSize) {
}
