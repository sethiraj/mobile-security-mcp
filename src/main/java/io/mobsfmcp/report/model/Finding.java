// SPDX-License-Identifier: MIT
package io.mobsfmcp.report.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One security finding, from either a static or a dynamic scan.
 *
 * @param id stable identifier, e.g. {@code static.manifest.clear_text_traffic}
 * @param source {@code static} or {@code dynamic}
 * @param category manifest, code, network, crypto, storage, permissions, secrets, tls,
 * runtime, certificate or tracker
 * @param severity MobSF's severity: high, warning, info, secure or hotspot
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Finding(String id, String source, String category, String severity, String title, String description,
		List<Evidence> evidence, Standards standards, String remediation) {

	/** Where the finding was observed. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Evidence(String file, String line, String snippet) {
	}

	/** Cross-references to security standards, as MobSF reports them. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Standards(String cwe, String owaspMobile, String masvs, Double cvss, String reference) {
	}

}
