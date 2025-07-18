package org.snomed.snowstorm.fhir.services;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.ValueSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.snomed.snowstorm.fhir.domain.FHIRPackageIndex;
import org.snomed.snowstorm.fhir.domain.FHIRPackageIndexFile;
import org.snomed.snowstorm.fhir.domain.FHIRValueSet;
import org.snomed.snowstorm.fhir.pojo.FHIRCodeSystemVersionParams;
import org.snomed.snowstorm.fhir.pojo.ValueSetExpansionParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.snomed.snowstorm.fhir.services.FHIRHelper.DEFAULT_VERSION;

@Service
public class FHIRLoadPackageService {

	@Value("${snowstorm.rest-api.readonly}")
	private boolean readOnlyMode;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private FHIRCodeSystemService codeSystemService;

	@Autowired
	private FHIRValueSetService valueSetService;

	@Autowired
	private FHIRConceptService fhirConceptService;

	@Autowired
	private FhirContext fhirContext;

	private final Logger logger = LoggerFactory.getLogger(getClass());

	public void uploadPackageResources(File packageFile, Set<String> resourceUrlsToImport, String submittedFileName, boolean testValueSets) throws IOException {
		FHIRHelper.readOnlyCheck(readOnlyMode);
		JsonParser jsonParser = (JsonParser) fhirContext.newJsonParser();
		FHIRPackageIndex index = extractObject(new FileInputStream(packageFile), ".index.json", FHIRPackageIndex.class, jsonParser);
		Set<String> supportedResourceTypes = Set.of("CodeSystem", "ValueSet");
		boolean importAll = resourceUrlsToImport.contains("*");
		List<FHIRPackageIndexFile> filesToImport = index.getFiles().stream()
				.filter(file -> (importAll && supportedResourceTypes.contains(file.getResourceType())) ||
						(!importAll && resourceUrlsToImport.contains(file.getUrl())))
				.toList();
		validateResources(filesToImport, resourceUrlsToImport, importAll, supportedResourceTypes);
		logger.info("Importing {} resources, found within index of package {}.{}", filesToImport.size(), submittedFileName,
				testValueSets ? " Each value set will be expanded and any issues logged as warning." : "");

		// Import all code systems
		for (FHIRPackageIndexFile indexFileToImport : filesToImport.stream().filter(file -> file.getResourceType().equals("CodeSystem")).collect(Collectors.toList())) {
			String filename = indexFileToImport.getFilename();
			String id = indexFileToImport.getId();
			String url = indexFileToImport.getUrl();
			if (id != null && url != null) {
				CodeSystem codeSystem = extractObject(new FileInputStream(packageFile), filename, CodeSystem.class, jsonParser);
				codeSystem.setId(id);
				codeSystem.setUrl(url);
				if (FHIRHelper.isSnomedUri(codeSystem.getUrl())) {
					logger.info("Skipping import of SNOMED CT code system via package. Please use the native SNOMED-CT API RF2 import.");
					continue;
				}
				String version = indexFileToImport.getVersion();
				FHIRCodeSystemVersion existingCodeSystemVersion = codeSystemService.findCodeSystemVersion(new FHIRCodeSystemVersionParams(url).setVersion(version));
				if (existingCodeSystemVersion != null) {
					if (codeSystem.getContent() == CodeSystem.CodeSystemContentMode.NOTPRESENT) {
						logger.info("Skipping import of CodeSystem {} with 'content:not-present' because a CodeSystem with the same url and version already exists.", existingCodeSystemVersion.getUrl());
						continue;
					} else {
						logger.info("Deleting existing CodeSystem and concepts for url:{}, version:{}", existingCodeSystemVersion.getUrl(), existingCodeSystemVersion.getVersion());
						codeSystemService.deleteCodeSystemVersion(existingCodeSystemVersion);
					}
				}
				logger.info("Creating CodeSystem {}", codeSystem.getUrl());
				FHIRCodeSystemVersion codeSystemVersion;
				try {
					codeSystemVersion = codeSystemService.createUpdate(codeSystem);
				} catch (ServiceException e) {
					throw new IOException("Failed to create FHIR CodeSystem.", e);
				}
				List<CodeSystem.ConceptDefinitionComponent> concepts = getConcepts(codeSystem, codeSystemVersion);
				logger.info("Importing CodeSystem {} with {} concepts from package", codeSystem.getUrl(), concepts != null ? concepts.size() : 0);
				if (concepts != null ) {
					fhirConceptService.saveAllConceptsOfCodeSystemVersion(concepts, codeSystemVersion);
				}
			}
		}

		// Import all value sets
		for (FHIRPackageIndexFile indexFileToImport : filesToImport.stream().filter(file -> file.getResourceType().equals("ValueSet")).collect(Collectors.toList())) {
			String filename = indexFileToImport.getFilename();
			String id = indexFileToImport.getId();
			String url = indexFileToImport.getUrl();
			if (id != null && url != null) {
				ValueSet valueSet = extractObject(new FileInputStream(packageFile), filename, ValueSet.class, jsonParser);
				valueSet.setId(id);
				valueSet.setUrl(url);
				valueSet.setVersion(indexFileToImport.getVersion());
				logger.info("Importing ValueSet {} {} from package", valueSet.getUrl(), valueSet.getVersion());
				valueSetService.createOrUpdateValuesetWithoutExpandValidation(valueSet);
				if (testValueSets) {
					try {
						valueSetService.expand(new ValueSetExpansionParameters(valueSet, true, true), null);
					} catch (SnowstormFHIRServerResponseException e) {
						logger.warn("Failed to expand ValueSet {}, {}", valueSet.getUrl(), e.getMessage());
					}
				}
			}
		}

		logger.info("Completed import of package {}.", submittedFileName);
	}

	private List<CodeSystem.ConceptDefinitionComponent> getConcepts(CodeSystem codeSystem, FHIRCodeSystemVersion version) {
		List<CodeSystem.ConceptDefinitionComponent> concepts;
		if (StringUtils.isEmpty(codeSystem.getSupplements())){
			concepts = codeSystem.getConcept();
		} else {
			CodeSystem newCodeSystem = codeSystemService.addSupplementToCodeSystem(codeSystem, version);
			concepts = newCodeSystem.getConcept();
		}





		return concepts;
	}

	private static void validateResources(List<FHIRPackageIndexFile> filesToImport, Set<String> resourceUrlsToImport, boolean importAll, Set<String> supportedResourceTypes) {
		for (FHIRPackageIndexFile fhirPackageIndexFile : filesToImport) {
			if (importAll && !supportedResourceTypes.contains(fhirPackageIndexFile.getResourceType())) {
				throw FHIRHelper.exception(format("Resource type '%s' is not supported for package based import.", fhirPackageIndexFile.getResourceType()),
						OperationOutcome.IssueType.NOTSUPPORTED, 400);
			}
			if (fhirPackageIndexFile.getVersion() == null) {
				fhirPackageIndexFile.version = DEFAULT_VERSION;
			}
		}
		if (!importAll) {
			Set<String> resourcesNotFound = new HashSet<>(resourceUrlsToImport);
			resourcesNotFound.removeAll(filesToImport.stream().map(FHIRPackageIndexFile::getUrl).collect(Collectors.toList()));
			if (!resourcesNotFound.isEmpty()) {
				throw FHIRHelper.exception(format("Failed to find resources (%s) within package index.", resourcesNotFound), OperationOutcome.IssueType.NOTFOUND, 400);
			}
		}
	}

	private <T> T extractObject(InputStream packageStream, String archiveEntryName, Class<T> clazz, JsonParser jsonParser) throws IOException {
		try (GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(packageStream);
			 TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {

			ArchiveEntry entry;
			while ((entry = tarIn.getNextEntry()) != null) {
				if (entry.getName().replace("package/", "").equals(archiveEntryName)) {
					logger.debug("Reading {}", entry.getName());
					if (archiveEntryName.equals(".index.json")) {
						return mapper.readValue(tarIn, clazz);
					} else {
						IBaseResource iBaseResource = jsonParser.parseResource(tarIn);
						return (T) iBaseResource;
					}
				}
			}
		}
		throw FHIRHelper.exception(format("File '%s' not found within package.", archiveEntryName), OperationOutcome.IssueType.NOTFOUND, 401);
	}

	public boolean isReadOnlyMode() {
		return readOnlyMode;
	}

	public boolean verifyResourceExists(String resourceUrl) {
		FHIRCodeSystemVersion codeSystemVersion = codeSystemService.findCodeSystemVersion(new FHIRCodeSystemVersionParams(resourceUrl));
		if (codeSystemVersion != null) {
			return true;
		}
		Optional<FHIRValueSet> valueSet = valueSetService.findLatestByUrl(resourceUrl);
		return valueSet.isPresent();
	}
}
