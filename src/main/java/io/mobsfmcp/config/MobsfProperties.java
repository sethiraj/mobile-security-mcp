// SPDX-License-Identifier: MIT
package io.mobsfmcp.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection and storage settings, bound from {@code mobsf.*} (env: {@code MOBSF_URL},
 * {@code MOBSF_API_KEY}, {@code UPLOAD_DIR}, {@code REPORT_DIR}).
 * <p>
 * Nothing here is required at startup so registries and inspectors can list the tools
 * without credentials; tools report missing configuration when called.
 */
@ConfigurationProperties("mobsf")
public record MobsfProperties(
		@DefaultValue("http://localhost:8000") String url,
		String apiKey,
		@DefaultValue("/data/apks") Path uploadDir,
		@DefaultValue("/data/reports") Path reportDir,
		@DefaultValue("5s") Duration connectTimeout,
		@DefaultValue("120s") Duration readTimeout,
		@DefaultValue("20m") Duration scanTimeout,
		@DefaultValue("500") int maxUploadMb) {

	public long maxUploadBytes() {
		return (long) maxUploadMb * 1024 * 1024;
	}

	public boolean hasApiKey() {
		return apiKey != null && !apiKey.isBlank();
	}

	/** API key with everything but the last 4 characters masked, for logs and diagnostics. */
	public String maskedApiKey() {
		if (!hasApiKey()) {
			return "<not set>";
		}
		return apiKey.length() <= 4 ? "****" : "****" + apiKey.substring(apiKey.length() - 4);
	}

	@Override
	public String toString() {
		return "MobsfProperties[url=" + url + ", apiKey=" + maskedApiKey() + ", uploadDir=" + uploadDir
				+ ", reportDir=" + reportDir + "]";
	}

}
