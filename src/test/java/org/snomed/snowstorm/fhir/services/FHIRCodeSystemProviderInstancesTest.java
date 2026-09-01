package org.snomed.snowstorm.fhir.services;


import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.CodeSystem;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Comparator;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FHIRCodeSystemProviderInstancesTest extends AbstractFHIRTest {

	@Test
	void testCodeSystemRecovery() {
		String url = baseUrl + "/CodeSystem";
		ResponseEntity<String> response = this.restTemplate.exchange(url, HttpMethod.GET, defaultRequestEntity, String.class);
		expectResponse(response, 200);
		Bundle bundle = fhirJsonParser.parseResource(Bundle.class, response.getBody());
		assertNotNull(bundle.getEntry());
		assertEquals(3, bundle.getEntry().size(), () -> {
			StringBuilder buffer = new StringBuilder();
			for (BundleEntryComponent component : bundle.getEntry()) {
				buffer.append(component.getFullUrl()).append(" ");
			}
			return buffer.toString();
		});
		for (BundleEntryComponent entry : bundle.getEntry()) {
			CodeSystem cs = (CodeSystem) entry.getResource();
			assertTrue(cs.getTitle().contains("SNOMED CT") || cs.getTitle().contains("ICD-10"), () -> "Found title " + cs.getTitle());
		}
	}
	
	@Test
	void testCodeSystemRecoverySorted() {
		String url = baseUrl + "/CodeSystem?_sort=title,-date";
		ResponseEntity<String> response = this.restTemplate.exchange(url, HttpMethod.GET, defaultRequestEntity, String.class);
		expectResponse(response, 200);
		Bundle bundle = fhirJsonParser.parseResource(Bundle.class, response.getBody());
		assertNotNull(bundle.getEntry());
		assertEquals(3, bundle.getEntry().size());
		List<CodeSystem> codeSystems = bundle.getEntry().stream()
				.map(BundleEntryComponent::getResource)
				.map(CodeSystem.class::cast)
				.toList();

		for (BundleEntryComponent entry : bundle.getEntry()) {
			CodeSystem cs = (CodeSystem)(entry.getResource());
			assertTrue(cs.getTitle().contains("SNOMED CT") || cs.getTitle().contains("ICD-10"), () -> "Found title " + cs.getTitle());
		}

		Comparator<String> nullSafeTitleComparator = Comparator.nullsFirst(Comparator.naturalOrder());
		Comparator<Date> nullSafeDateComparator = Comparator.nullsFirst(Comparator.naturalOrder());
		Comparator<CodeSystem> expectedOrder = Comparator
				.comparing(CodeSystem::getTitle, nullSafeTitleComparator)
				.thenComparing(Comparator.comparing(CodeSystem::getDate, nullSafeDateComparator).reversed());
		assertEquals(codeSystems.stream().sorted(expectedOrder).toList(), codeSystems,
				"Code systems should be sorted by title ascending, then date descending");
	}
	
	@Test
	void testCodeSystemRecoverySortedExpectedFail() {
		String url = baseUrl + "/CodeSystem?_sort=foo,-bar";
		ResponseEntity<String> response = this.restTemplate.exchange(url, HttpMethod.GET, defaultRequestEntity, String.class);
		expectResponse(response, 400);
	}
	
}
