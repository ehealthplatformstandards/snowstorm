package org.snomed.snowstorm.fhir.services;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class FHIRLoadPackageServiceTest extends AbstractFHIRTest {
	private static final String RESOURCE_ID = "device-status-reason";
	private static final String VERSION = "0.1.0";
	private static final String CODE_SYSTEM_VERSION_ID = RESOURCE_ID + "-" + VERSION;

	@Autowired
	private FHIRLoadPackageService service;

	private File packageFile;

	@BeforeEach
	public void testSetup() throws IOException {
		// Create compressed archive for the test FHIR Package file
		packageFile = Files.createTempFile(getClass().getSimpleName() + "test-file", "tgz").toFile();
		try (GzipCompressorOutputStream gzipOut = new GzipCompressorOutputStream(new FileOutputStream(packageFile));
			TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {

			File packageDir = new File("src/test/resources/dummy-fhir-content/tiny-package");
			File[] files = packageDir.listFiles();
			if (files != null) {
				for (File file : files) {
					if (file.isFile()) {
						TarArchiveEntry archiveEntry = (TarArchiveEntry) tarOut.createArchiveEntry(file, file.getName());
						tarOut.putArchiveEntry(archiveEntry);
						Files.copy(file.toPath(), tarOut);
						tarOut.closeArchiveEntry();
					}
				}
			}
		}
	}

	@AfterEach
	public void testAfter() throws IOException {
		valueSetRepository.deleteById(RESOURCE_ID);
		codeSystemRepository.deleteById(CODE_SYSTEM_VERSION_ID);
		if (packageFile != null) {
			Files.deleteIfExists(packageFile.toPath());
		}
	}

	@Test
	void uploadPackageResources() throws IOException {
		assertFalse(codeSystemRepository.findById(CODE_SYSTEM_VERSION_ID).isPresent());
		assertFalse(valueSetRepository.findById(RESOURCE_ID).isPresent());

		service.uploadPackageResources(packageFile, Collections.singleton("*"), packageFile.getName(), true);

		assertTrue(codeSystemRepository.findById(CODE_SYSTEM_VERSION_ID).isPresent());
		assertTrue(valueSetRepository.findById(RESOURCE_ID).isPresent());

		// Expand imported implicit value set, that includes codes from imported code system
		//
		String testValueSetUri = "http://terminology.hl7.org/ValueSet/device-status-reason";
		ResponseEntity<String> response = restTemplate.exchange(baseUrl + "/ValueSet/$expand?url=" + testValueSetUri, HttpMethod.GET, null, String.class);
		assertEquals(HttpStatus.OK, response.getStatusCode());
		String valueSetString = response.getBody();
		ValueSet valueSet = fhirJsonParser.parseResource(ValueSet.class, valueSetString);
		assertNotNull(valueSet);
		assertEquals(testValueSetUri, valueSet.getUrl());
		assertEquals(VERSION, valueSet.getVersion());
		assertEquals(8, valueSet.getExpansion().getContains().size());
	}

}
