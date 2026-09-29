// SPDX-License-Identifier: MIT
package io.mobsfmcp.report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import io.mobsfmcp.report.model.ScanReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generates {@code schemas/mobsf-mcp-report.v1.json} from the report records and fails
 * when the committed file has drifted, so the published schema always matches the code.
 * Run with {@code -Dmobsf.schema.write=true} to update the file after an intended change.
 */
class ReportSchemaTest {

	static final Path SCHEMA_FILE = Path.of("schemas", "mobsf-mcp-report.v1.json");

	@Test
	void committedSchemaMatchesTheReportRecords() throws Exception {
		// no EXTRA_OPEN_API_FORMAT_VALUES: formats like "double" are OpenAPI-only and strict
		// JSON Schema validators (ajv) reject them
		SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12,
				OptionPreset.PLAIN_JSON)
			.with(Option.PLAIN_DEFINITION_KEYS)
			// so ScanType's @JsonValue lowercase form ends up in the schema, matching the JSON
			.with(new JacksonModule(JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE));
		// toPrettyString uses the platform line separator; the committed file is always LF
		String generated = new SchemaGenerator(builder.build()).generateSchema(ScanReport.class)
			.toPrettyString()
			.replace("\r\n", "\n") + "\n";

		if (Boolean.getBoolean("mobsf.schema.write")) {
			Files.createDirectories(SCHEMA_FILE.getParent());
			Files.writeString(SCHEMA_FILE, generated, StandardCharsets.UTF_8);
		}

		assertThat(SCHEMA_FILE).as("run `mvn test -Dmobsf.schema.write=true` to regenerate").exists();
		assertThat(Files.readString(SCHEMA_FILE, StandardCharsets.UTF_8).replace("\r\n", "\n"))
			.as("schemas/mobsf-mcp-report.v1.json is out of date; regenerate it with -Dmobsf.schema.write=true")
			.isEqualTo(generated);
	}

	@Test
	void schemaDescribesTheFieldsAgentsRelyOn() throws Exception {
		String schema = Files.readString(SCHEMA_FILE, StandardCharsets.UTF_8);

		assertThat(schema).contains("schemaVersion")
			.contains("findings")
			.contains("severity")
			.contains("securityScore")
			.contains("evidence")
			.contains("standards");
	}

}
