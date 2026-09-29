// SPDX-License-Identifier: MIT
package io.mobsfmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention defaults, bound from {@code mobsf-mcp.retention.*} (env:
 * {@code MCP_RETENTION_*}).
 *
 * @param enabled when false, {@code prune_scans} stays visible but refuses to run and
 * explains how to turn it on — a disappearing tool would look like a bug
 * @param keepPerApp scans kept per app when a prune does not name its own limit
 * @param maxAgeDays delete scans older than this; 0 means no age limit
 */
@ConfigurationProperties("mobsf-mcp.retention")
public record RetentionProperties(@DefaultValue("true") boolean enabled, @DefaultValue("5") int keepPerApp,
		@DefaultValue("0") int maxAgeDays) {
}
