package org.snomed.snowstorm.fhir.services;

import jakarta.servlet.http.HttpServletRequest;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.RestfulServerConfiguration;
import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import org.hl7.fhir.instance.model.api.IBaseConformance;
import ca.uhn.fhir.rest.annotation.Metadata;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * See https://www.hl7.org/fhir/terminologycapabilities.html
 * See https://smilecdr.com/hapi-fhir/docs/server_plain/introduction.html#capability-statement-server-metadata
 * Call using GET [base]/metadata?mode=terminology
 * See https://github.com/jamesagnew/hapi-fhir/issues/1681
 */
public class FHIRTerminologyCapabilitiesProvider extends ServerCapabilityStatementProvider {
	private final Logger logger = LoggerFactory.getLogger(getClass());
	public FHIRTerminologyCapabilitiesProvider(RestfulServer theServer) {
		super(theServer);
	}

	public FHIRTerminologyCapabilitiesProvider(FhirContext theContext, RestfulServerConfiguration theServerConfiguration) {
		super(theContext, theServerConfiguration);
	}

	public FHIRTerminologyCapabilitiesProvider(RestfulServer theRestfulServer, ISearchParamRegistry theSearchParamRegistry, IValidationSupport theValidationSupport) {
		super(theRestfulServer, theSearchParamRegistry, theValidationSupport);
	}

	@Metadata
	public IBaseConformance getMetadataResource(HttpServletRequest request, RequestDetails requestDetails) {
		logger.info(requestDetails.getCompleteUrl());
		if (request.getParameter("mode") != null && request.getParameter("mode").equals("terminology")) {
			return new FHIRTerminologyCapabilities().withDefaults();
		} else {
			return super.getServerConformance(request, requestDetails);
		}
	}
}
