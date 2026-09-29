// SPDX-License-Identifier: MIT
package io.mobsfmcp.report;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import io.mobsfmcp.config.MobsfProperties;
import io.mobsfmcp.report.model.ScanReport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * Writes reports to {@code <reportDir>/<hash>/} so agents, CI jobs and other containers
 * can pick them up later. Files are written to a temporary name and then moved, so a
 * reader never sees a half-written report.
 */
@Component
public class ReportStore {

	private final MobsfProperties properties;

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	public ReportStore(MobsfProperties properties) {
		this.properties = properties;
	}

	public enum Format {

		NORMALIZED, RAW, SARIF;

		public static Format parse(String value) {
			if (value == null || value.isBlank()) {
				return NORMALIZED;
			}
			return switch (value.trim().toLowerCase(Locale.ROOT)) {
				case "normalized" -> NORMALIZED;
				case "raw" -> RAW;
				case "sarif" -> SARIF;
				default -> throw new IllegalArgumentException(
						"Unknown format '" + value + "'. Use 'normalized', 'raw' or 'sarif'.");
			};
		}
	}

	public Path write(String hash, ScanReport.ScanType type, ScanReport report) {
		return writeJson(path(hash, type, Format.NORMALIZED), jsonMapper.valueToTree(report));
	}

	public Path writeRaw(String hash, ScanReport.ScanType type, JsonNode raw) {
		return writeJson(path(hash, type, Format.RAW), raw);
	}

	public Path writeSarif(String hash, ScanReport.ScanType type, JsonNode sarif) {
		return writeJson(path(hash, type, Format.SARIF), sarif);
	}

	public Path path(String hash, ScanReport.ScanType type, Format format) {
		String suffix = switch (format) {
			case RAW -> ".raw.json";
			case SARIF -> ".sarif.json";
			case NORMALIZED -> ".json";
		};
		return directory(hash).resolve(type.name().toLowerCase(Locale.ROOT) + suffix);
	}

	public Path directory(String hash) {
		return properties.reportDir().toAbsolutePath().normalize().resolve(hash);
	}

	public JsonNode read(Path path) {
		if (!Files.isRegularFile(path)) {
			throw new IllegalArgumentException("No report at " + path
					+ ". Run export_report (or scan_app followed by export_report) for this hash first.");
		}
		try {
			return jsonMapper.readTree(Files.readString(path));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not read " + path + ": " + ex.getMessage(), ex);
		}
	}

	private Path writeJson(Path target, JsonNode content) {
		try {
			Files.createDirectories(target.getParent());
			Path temporary = Files.createTempFile(target.getParent(), ".tmp-", ".json");
			Files.writeString(temporary, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(content));
			try {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException ex) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
			return target;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not write " + target + ": " + ex.getMessage()
					+ ". Check that the report directory is mounted and writable.", ex);
		}
	}

}
