// SPDX-License-Identifier: MIT
package io.mobsfmcp.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import io.mobsfmcp.config.MobsfProperties;
import io.mobsfmcp.report.ReportStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * HTTP-mode extras for clients that are not on the same machine as the server: an app can
 * be uploaded over HTTP instead of a mounted directory, and exported reports can be
 * fetched without MCP. Both are behind the bearer token.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UploadController {

	private final MobsfProperties properties;

	private final UploadStore uploadStore;

	private final ReportStore reportStore;

	public UploadController(MobsfProperties properties, UploadStore uploadStore, ReportStore reportStore) {
		this.properties = properties;
		this.uploadStore = uploadStore;
		this.reportStore = reportStore;
	}

	public record UploadResponse(String uploadId, String fileName, long sizeBytes, String nextStep) {
	}

	/** Stores the app and returns an {@code uploadId} the {@code upload_app} tool accepts. */
	@PostMapping(path = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty()) {
			throw new InvalidFileInputException("The uploaded file is empty.");
		}
		String fileName = file.getOriginalFilename() == null ? "app.apk" : file.getOriginalFilename();
		String uploadId = uploadStore.store(file.getInputStream(), fileName);
		return new UploadResponse(uploadId, fileName, file.getSize(),
				"Call the upload_app tool with uploadId=" + uploadId + ".");
	}

	/** Serves a report written by {@code export_report}, e.g. {@code /reports/<hash>/static.json}. */
	@GetMapping(path = "/reports/{hash}/{file}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Resource> report(@PathVariable String hash, @PathVariable String file) {
		if (!hash.matches("[A-Za-z0-9]{1,64}") || !file.matches("[a-z]+(\\.raw)?\\.json")) {
			return ResponseEntity.badRequest().build();
		}
		Path path = reportStore.directory(hash).resolve(file);
		if (!Files.isRegularFile(path)) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + hash + "-" + file + "\"")
			.body(new FileSystemResource(path));
	}

	/** Serves the PDF written by {@code download_pdf}. */
	@GetMapping(path = "/reports/{hash}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
	public ResponseEntity<Resource> pdf(@PathVariable String hash) {
		if (!hash.matches("[A-Za-z0-9]{1,64}")) {
			return ResponseEntity.badRequest().build();
		}
		Path path = properties.reportDir().toAbsolutePath().normalize().resolve(hash + ".pdf");
		if (!Files.isRegularFile(path)) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok().body(new FileSystemResource(path));
	}

	/** Random id used as the stored file name; never taken from client input. */
	static String newUploadId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

}
