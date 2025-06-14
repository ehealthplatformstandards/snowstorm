package org.snomed.snowstorm.fhir.services;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.provider.TerminologyUploaderProvider;
import ca.uhn.fhir.jpa.term.TermLoaderSvcImpl;
import ca.uhn.fhir.jpa.term.api.ITermLoaderSvc;
import ca.uhn.fhir.rest.annotation.*;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.PatchTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.QuantityParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.instance.model.api.IBaseParameters;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.ICompositeType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.jetbrains.annotations.NotNull;
import org.snomed.snowstorm.core.data.domain.Concept;
import org.snomed.snowstorm.core.data.domain.Description;
import org.snomed.snowstorm.core.data.services.CodeSystemService;
import org.snomed.snowstorm.core.data.services.MultiSearchService;
import org.snomed.snowstorm.core.data.services.ServiceException;
import org.snomed.snowstorm.core.data.services.postcoordination.ExpressionValidationException;
import org.snomed.snowstorm.core.data.services.postcoordination.model.ComparableExpression;
import org.snomed.snowstorm.core.data.services.postcoordination.model.PostCoordinatedExpression;
import org.snomed.snowstorm.core.pojo.LanguageDialect;
import org.snomed.snowstorm.fhir.config.FHIRConstants;
import org.snomed.snowstorm.fhir.domain.FHIRCodeSystemVersion;
import org.snomed.snowstorm.fhir.domain.FHIRConcept;
import org.snomed.snowstorm.fhir.domain.SearchFilter;
import org.snomed.snowstorm.fhir.domain.SubsumesResult;
import org.snomed.snowstorm.fhir.pojo.ConceptAndSystemResult;
import org.snomed.snowstorm.fhir.pojo.FHIRCodeSystemVersionParams;
import org.snomed.snowstorm.fhir.pojo.PatchCode;
import org.snomed.snowstorm.fhir.pojo.PatchOperation;
import org.snomed.snowstorm.syndication.services.importers.fixedversion.ucum.UcumCodeValidationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.kaicode.elasticvc.api.ComponentService.LARGE_PAGE;
import static java.lang.String.format;
import static java.util.stream.Stream.concat;
import static org.snomed.snowstorm.fhir.services.FHIRCodeSystemService.SCT_ID_PREFIX;
import static org.snomed.snowstorm.fhir.services.FHIRHelper.*;

@Component
public class FHIRCodeSystemProvider implements IResourceProvider, FHIRConstants {

	private static final String PARAM_SYSTEM = "system";
	private static final String PARAM_FILE = "file";
	private static final String EXAMPLE_SNOMEDCT_PATCH = "[ { \"op\": \"add\", \"path\": \"/concept\", \"value\": { \"code\": \"POSTCOORDINATED_EXPRESSION\" } } ]";

	@Value("${snowstorm.rest-api.readonly}")
	private boolean readOnlyMode;

	@Autowired
	private FHIRLoadPackageService loadPackageService;

	@Autowired
	private FhirContext fhirContext;

	@Autowired
	private FHIRCodeSystemService fhirCodeSystemService;

	@Autowired
	private CodeSystemService snomedCodeSystemService;

	@Autowired
	private MultiSearchService snomedMultiSearchService;

	@Autowired
	private FHIRGraphService graphService;

	@Autowired
	private HapiParametersMapper pMapper;

	@Autowired
	private FHIRHelper fhirHelper;

	@Autowired
	private FHIRTermCodeSystemStorage termCodeSystemStorage;

	@Autowired
	private FHIRConceptService fhirConceptService;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private UcumCodeValidationService ucumCodeValidationService;

	private static final String[] defaultSortOrder = new String[] { "title", "-date" };

	private static final Comparator<String> nullSafeStringComparator = Comparator.nullsFirst(Comparator.naturalOrder());
	private static final Comparator<Date> nullSafeDateComparator = Comparator.nullsFirst(Comparator.naturalOrder());
	//See https://stackoverflow.com/questions/61073018/streams-sorting-by-runtime-parameter
	private static final Map<String, Comparator<CodeSystem>> comparatorMap = Map.ofEntries(
			new AbstractMap.SimpleEntry<>("_id", Comparator.comparing(CodeSystem::getId, nullSafeStringComparator)),
			new AbstractMap.SimpleEntry<>("-_id", Comparator.comparing(CodeSystem::getId, nullSafeStringComparator).reversed()),
			new AbstractMap.SimpleEntry<>("date", Comparator.comparing(CodeSystem::getDate, nullSafeDateComparator)),
			new AbstractMap.SimpleEntry<>("-date", Comparator.comparing(CodeSystem::getDate, nullSafeDateComparator).reversed()),
			new AbstractMap.SimpleEntry<>("description", Comparator.comparing(CodeSystem::getDescription, nullSafeStringComparator)),
			new AbstractMap.SimpleEntry<>("-description", Comparator.comparing(CodeSystem::getDescription, nullSafeStringComparator).reversed()),
			new AbstractMap.SimpleEntry<>("name", Comparator.comparing(CodeSystem::getName, nullSafeStringComparator)),
			new AbstractMap.SimpleEntry<>("-name", Comparator.comparing(CodeSystem::getName, nullSafeStringComparator).reversed()),
			new AbstractMap.SimpleEntry<>("publisher", Comparator.comparing(CodeSystem::getPublisher, nullSafeStringComparator)),
			new AbstractMap.SimpleEntry<>("-publisher", Comparator.comparing(CodeSystem::getPublisher, nullSafeStringComparator).reversed()),
			new AbstractMap.SimpleEntry<>("title", Comparator.comparing(CodeSystem::getTitle, nullSafeStringComparator)),
			new AbstractMap.SimpleEntry<>("-title", Comparator.comparing(CodeSystem::getTitle, nullSafeStringComparator).reversed()),
			new AbstractMap.SimpleEntry<>("url", Comparator.comparing(CodeSystem::getUrl, nullSafeStringComparator)),
			new AbstractMap.SimpleEntry<>("-url", Comparator.comparing(CodeSystem::getUrl, nullSafeStringComparator).reversed()),
			new AbstractMap.SimpleEntry<>("version", Comparator.comparing(CodeSystem::getVersion, nullSafeStringComparator)),
			new AbstractMap.SimpleEntry<>("-version", Comparator.comparing(CodeSystem::getVersion, nullSafeStringComparator).reversed())
		);

	//See https://www.hl7.org/fhir/valueset.html#search
	@Search
	public List<CodeSystem> findCodeSystems(
			RequestDetails theRequest,
			HttpServletResponse theResponse,
			@OptionalParam(name="id") String id,
			@OptionalParam(name="code") String code,
			@OptionalParam(name="context") TokenParam context,
			@OptionalParam(name="context-quantity") QuantityParam contextQuantity,
			@OptionalParam(name="context-type") String contextType,
			@OptionalParam(name="date") StringParam date,
			@OptionalParam(name="description") StringParam description,
			@OptionalParam(name="identifier") StringParam identifier,
			@OptionalParam(name="jurisdiction") StringParam jurisdiction,
			@OptionalParam(name="name") StringParam name,
			@OptionalParam(name="publisher") StringParam publisher,
			@OptionalParam(name="reference") StringParam reference,
			@OptionalParam(name="status") String status,
			@OptionalParam(name="title") StringParam title,
			@OptionalParam(name="url") String url,
			@OptionalParam(name="version") StringParam version) {

		SearchFilter csFilter = new SearchFilter()
									.withId(id)
									.withCode(code)
									.withContext(context)
									.withContextQuantity(contextQuantity)
									.withContextType(contextType)
									.withDate(date)
									.withDescription(description)
									.withIdentifier(identifier)
									.withJurisdiction(jurisdiction)
									.withName(name)
									.withPublisher(publisher)
									.withReference(reference)
									.withStatus(status)
									.withTitle(title)
									.withUrl(url)
									.withVersion(version);

		List<String> sortOn;
		if (theRequest.getParameters().get("_sort") != null) {
			sortOn = new ArrayList<>();
			for (String param : theRequest.getParameters().get("_sort")) {
				sortOn.addAll(Arrays.asList(param.split(",")));
			}
		} else {
			sortOn = Arrays.asList(defaultSortOrder);
		}

		for (String sortField : sortOn) {
			if (!comparatorMap.containsKey(sortField)) {
				throw exception(sortField + " is not supported as a field to sort on.", IssueType.INVALID, 400);
			}
		}

		Comparator<CodeSystem> chainedComparator =
				sortOn.stream().map(comparatorMap::get).reduce(Comparator::thenComparing).orElseGet(() -> Comparator.comparing(CodeSystem::getId));

		Stream<CodeSystem> snomedCodeSystemStream = snomedMultiSearchService.getAllPublishedVersions().stream()
				.map(snomedSystemVersion -> new FHIRCodeSystemVersion(snomedSystemVersion).toHapiCodeSystem());

		Stream<CodeSystem> snomedPostcoordinatedStream = snomedCodeSystemService.findAllPostcoordinatedBrief().stream()
				.map(snomedSystem -> new FHIRCodeSystemVersion(snomedSystem).toHapiCodeSystem());

		Stream<CodeSystem> fhirCodeSystemStream = StreamSupport.stream(fhirCodeSystemService.findAll().spliterator(), false)
				.map(FHIRCodeSystemVersion::toHapiCodeSystem);

		return concat(concat(snomedCodeSystemStream, fhirCodeSystemStream), snomedPostcoordinatedStream)
				.filter(cs -> csFilter.apply(cs, fhirHelper))
				.sorted(chainedComparator)
				.toList();
	}

	@Read
	public CodeSystem getCodeSystem(@IdParam IdType id) {
		return getFhirCodeSystemVersionOrThrow(id.getIdPart()).toHapiCodeSystem();
	}

	@NotNull
	private FHIRCodeSystemVersion getFhirCodeSystemVersionOrThrow(String idPart) {
		FHIRCodeSystemVersion fhirCodeSystemVersion = null;
		if (!idPart.startsWith(SCT_ID_PREFIX)) {
			Optional<FHIRCodeSystemVersion> fhirCodeSystem = fhirCodeSystemService.findById(idPart);
			if (fhirCodeSystem.isPresent()) {
				fhirCodeSystemVersion = fhirCodeSystem.get();
			}
		} else {
			Stream<FHIRCodeSystemVersion> snomedPublished = snomedMultiSearchService.getAllPublishedVersions().stream()
					.map(FHIRCodeSystemVersion::new);
			Stream<FHIRCodeSystemVersion> snomedPostcoordinated = snomedCodeSystemService.findAllPostcoordinatedBrief().stream()
					.map(FHIRCodeSystemVersion::new);

			Optional<FHIRCodeSystemVersion> snomedCodeSystem = Stream.concat(snomedPublished, snomedPostcoordinated)
					.filter(cs -> cs.getId().equals(idPart))
					.findAny();

			if (snomedCodeSystem.isPresent()) {
				fhirCodeSystemVersion = snomedCodeSystem.get();
			}
		}
		if (fhirCodeSystemVersion == null) {
			throw FHIRHelper.exception("Code System " + idPart + " not found", IssueType.NOTFOUND, 404);
		}
		return fhirCodeSystemVersion;
	}

	@Create
	public MethodOutcome createCodeSystem(@ResourceParam CodeSystem codeSystem) throws ServiceException {
		// HAPI clears the id in the codeSystem when using this POST method
		return doCreateUpdate(codeSystem);
	}

	@Update
	public MethodOutcome createUpdateCodeSystem(@IdParam IdType id, @ResourceParam CodeSystem codeSystem) throws ServiceException {
		// HAPI keeps the id in the codeSystem when using this PUT method. It also ensures that the id in the resource and URL match.
		return doCreateUpdate(codeSystem);
	}

	@NotNull
	private MethodOutcome doCreateUpdate(CodeSystem codeSystem) throws ServiceException {
		FHIRHelper.readOnlyCheck(readOnlyMode);
		MethodOutcome outcome = new MethodOutcome();
		FHIRCodeSystemVersion codeSystemVersion = fhirCodeSystemService.createUpdate(codeSystem);
		outcome.setId(new IdType("CodeSystem", codeSystemVersion.getId(), codeSystemVersion.getVersion()));
		return outcome;
	}

	@Patch
	public MethodOutcome addConcept(@IdParam IdType id, PatchTypeEnum patchType, @ResourceParam String thePatchBody) {
		FHIRHelper.readOnlyCheck(readOnlyMode);
		FHIRCodeSystemVersion codeSystemVersion = getFhirCodeSystemVersionOrThrow(id.getIdPart());
		if (codeSystemVersion.getSnomedCodeSystem() != null && !codeSystemVersion.getSnomedCodeSystem().isPostcoordinatedNullSafe()) {
			throw exception("Only SNOMED CT CodeSystem supplements can be patched.", IssueType.INVARIANT, 400);
		}

		List<String> expressionStrings = new ArrayList<>();
		try {
			TypeReference<List<PatchOperation>> valueTypeRef = new TypeReference<>() {};
			List<PatchOperation> patchOperations = objectMapper.readValue(thePatchBody, valueTypeRef);
			for (PatchOperation patchOperation : patchOperations) {
				if (!"add".equals(patchOperation.getOp()) || !"/concept".equals(patchOperation.getPath())) {
					throw exception("The only supported patch operation uses this format: " + EXAMPLE_SNOMEDCT_PATCH, IssueType.INVARIANT, 400);
				}
				PatchCode value = patchOperation.getValue();
				if (value == null || value.getCode() == null || value.getCode().isEmpty()) {
					throw exception("The code is missing from request.", IssueType.INVARIANT, 400);
				}
				if (value.getDisplay() != null) {
					throw exception("Display terms are not yet supported.", IssueType.NOTSUPPORTED, 400);
				}
				expressionStrings.add(value.getCode());
			}
		} catch (JsonProcessingException e) {
			throw exception("Failed to read patch body. The only supported patch operation uses this format: " + EXAMPLE_SNOMEDCT_PATCH, IssueType.INVARIANT, 400);
		}

		if (expressionStrings.isEmpty()) {
			return new MethodOutcome();
		}

		List<PostCoordinatedExpression> expressions = fhirCodeSystemService.addExpressions(codeSystemVersion, expressionStrings);

		CodeSystem savedCodeSystem = new CodeSystem();
		for (PostCoordinatedExpression expression : expressions) {
			CodeSystem.ConceptDefinitionComponent conceptDefinitionComponent = new CodeSystem.ConceptDefinitionComponent()
					.setCode(expression.getCloseToUserForm().replace(" ", ""))
					.addProperty(new CodeSystem.ConceptPropertyComponent(new CodeType("alternateIdentifier"), new StringType(expression.getId())));

				conceptDefinitionComponent
					.addProperty(new CodeSystem.ConceptPropertyComponent(
							new CodeType("humanReadableClassifiableForm"), new StringType(expression.getHumanReadableClassifiableForm())));
			if (expression.getHumanReadableNecessaryNormalForm() != null) {
				conceptDefinitionComponent
					.addProperty(new CodeSystem.ConceptPropertyComponent(
							new CodeType("humanReadableNecessaryNormalForm"), new StringType(expression.getHumanReadableNecessaryNormalForm())));
			}
			ComparableExpression nnf = expression.getNecessaryNormalFormExpression();
			if (nnf != null && nnf.getEquivalentConcepts() != null) {
				for (Long equivalentConcept : nnf.getEquivalentConcepts()) {
					conceptDefinitionComponent
							.addProperty(new CodeSystem.ConceptPropertyComponent(
									new CodeType("equivalentConcept"), new StringType(equivalentConcept.toString())));
				}
			}
			savedCodeSystem.addConcept(conceptDefinitionComponent);
		}
		return new MethodOutcome().setResource(savedCodeSystem);
	}

	@Delete
	public void deleteCodeSystem(
			@IdParam IdType id,
			@OptionalParam(name="url") UriType url,
			@OptionalParam(name="version") StringType version) {

		FHIRHelper.readOnlyCheck(readOnlyMode);
		FHIRCodeSystemVersionParams codeSystemVersionParams = getCodeSystemVersionParams(id, url, version, null);
		FHIRCodeSystemVersion codeSystemVersion = fhirCodeSystemService.findCodeSystemVersionOrThrow(codeSystemVersionParams);
		fhirCodeSystemService.deleteCodeSystemVersion(codeSystemVersion);
	}

	@Operation(name="$lookup", idempotent=true)
	public Parameters lookupImplicit(
			HttpServletRequest request,
			HttpServletResponse response,
			@OperationParam(name="code") CodeType code,
			@OperationParam(name="system") UriType system,
			@OperationParam(name="version") StringType version,
			@OperationParam(name="coding") Coding coding,
			@OperationParam(name="date") StringType date,
			@OperationParam(name="displayLanguage") String displayLanguage,
			@OperationParam(name="property") List<CodeType> propertiesType ) {

		mutuallyExclusive("code", code, "coding", coding);
		notSupported("date", date);
		FHIRCodeSystemVersionParams codeSystemVersion = getCodeSystemVersionParams(system, version, coding);
		return lookup(codeSystemVersion, fhirHelper.recoverCode(code, coding), displayLanguage, request.getHeader(ACCEPT_LANGUAGE_HEADER), propertiesType);
	}
	
	@Operation(name="$lookup", idempotent=true)
	public Parameters lookupInstance(
			@IdParam IdType id,
			HttpServletRequest request,
			HttpServletResponse response,
			@OperationParam(name="system") UriType system,
			@OperationParam(name="version") StringType version,
			@OperationParam(name="code") CodeType code,
			@OperationParam(name="coding") Coding coding,
			@OperationParam(name="date") StringType date,
			@OperationParam(name="displayLanguage") String displayLanguage,
			@OperationParam(name="property") List<CodeType> propertiesType ) {

		mutuallyExclusive("code", code, "coding", coding);
		notSupported("date", date);
		notSupported("system", system, " when id is already specified in the URL.");
		notSupported("version", version, " when id is already specified in the URL.");
		FHIRCodeSystemVersionParams codeSystemVersion = getCodeSystemVersionParams(id, system, version, coding);
		return lookup(codeSystemVersion, fhirHelper.recoverCode(code, coding), displayLanguage, request.getHeader(ACCEPT_LANGUAGE_HEADER), propertiesType);
	}
	
	private Parameters lookup(
			FHIRCodeSystemVersionParams codeSystemParams,
			String code,
			String displayLanguage,
			String acceptLanguageHeader,
			List<CodeType> propertiesType) {

		List<LanguageDialect> designations = new ArrayList<>();
		fhirHelper.setLanguageOptions(designations, displayLanguage, acceptLanguageHeader);
		if (codeSystemParams.isSnomed()) {
			ConceptAndSystemResult conceptAndSystemResult = fhirCodeSystemService.findSnomedConcept(code, designations, codeSystemParams);
			FHIRCodeSystemVersion codeSystemVersion = conceptAndSystemResult.codeSystemVersion();
			if (conceptAndSystemResult.concept() == null) {
				throw exception(format("Code '%s' not found for system '%s'.", code, codeSystemVersion.getUrl()), IssueType.NOTFOUND, 404);
			}

			List<String> childIds = conceptAndSystemResult.postcoordinated() ? Collections.emptyList() : graphService.findChildren(code, codeSystemVersion, LARGE_PAGE);
			Set<FhirSctProperty> properties = FhirSctProperty.parse(propertiesType);
			return pMapper.mapToFHIR(conceptAndSystemResult, childIds, properties, designations);
		} else {
			FHIRCodeSystemVersion fhirCodeSystemVersion = fhirCodeSystemService.findCodeSystemVersionOrThrow(codeSystemParams);
			FHIRConcept concept = fhirConceptService.findConcept(fhirCodeSystemVersion, code);
			if (concept == null) {
				throw exception(format("Code '%s' not found for system '%s'.", code, fhirCodeSystemVersion.getUrl()), IssueType.NOTFOUND, 404);
			}
			return pMapper.mapToFHIR(fhirCodeSystemVersion, concept);
		}
	}

	@Operation(name="$validate-code", idempotent=true)
	public Parameters validateCodeImplicit(
			HttpServletRequest request,
			HttpServletResponse response,
			@ResourceParam String rawBody,
			@OperationParam(name="url") UriType url,
			@OperationParam(name="codeSystem") StringType codeSystem,
			@OperationParam(name="code") CodeType code,
			@OperationParam(name="display") String display,
			@OperationParam(name="version") StringType version,
			@OperationParam(name="date") DateTimeType date,
			@OperationParam(name="coding") Coding coding,
			@OperationParam(name="displayLanguage") String displayLanguage) {

		notSupported("codeSystem", codeSystem);
		notSupported("date", date);
		mutuallyExclusive("code", code, "coding", coding);
		mutuallyRequired("display", display, "code", code, "coding", coding);
		if (request.getMethod().equals(RequestMethod.POST.name())) {
			// HAPI doesn't populate the OperationParam values for POST, we parse the body instead.
			List<Parameters.ParametersParameterComponent> parsed = fhirContext.newJsonParser().parseResource(Parameters.class, rawBody).getParameter();
			FHIRHelper.handleTxResources(loadPackageService,parsed);
		}
		FHIRCodeSystemVersionParams codeSystemParams = getCodeSystemVersionParams(null, url, version, coding);
		return validateCode(codeSystemParams, fhirHelper.recoverCode(code, coding), display, request.getHeader(ACCEPT_LANGUAGE_HEADER));
	}
	
	@Operation(name="$validate-code", idempotent=true)
	public Parameters validateCodeInstance(
			@IdParam IdType id,
			HttpServletRequest request,
			HttpServletResponse response,
			@OperationParam(name="url") UriType url,
			@OperationParam(name="codeSystem") StringType codeSystem,
			@OperationParam(name="code") CodeType code,
			@OperationParam(name="display") String display,
			@OperationParam(name="version") StringType version,
			@OperationParam(name="date") DateTimeType date,
			@OperationParam(name="coding") Coding coding,
			@OperationParam(name="displayLanguage") String displayLanguage) {

		FHIRCodeSystemVersionParams codeSystemParams = getCodeSystemVersionParams(id, url, version, coding);
		return validateCode(codeSystemParams, fhirHelper.recoverCode(code, coding), display, request.getHeader(ACCEPT_LANGUAGE_HEADER));
	}
	
	private Parameters validateCode(
			FHIRCodeSystemVersionParams codeSystemParams,
			String code,
			String display,
			String acceptLanguageHeader) {

		List<LanguageDialect> languageDialects = fhirHelper.getLanguageDialects(null, acceptLanguageHeader);
		if (codeSystemParams.isSnomed()) {
			ConceptAndSystemResult conceptAndSystemResult = fhirCodeSystemService.findSnomedConcept(code, languageDialects, codeSystemParams);
			Concept concept = conceptAndSystemResult.concept();
			FHIRCodeSystemVersion codeSystemVersion = conceptAndSystemResult.codeSystemVersion();

			boolean result = false;
			String message = conceptAndSystemResult.message();
			String displayOut = null;
			if (concept != null) {
				if (display == null) {
					result = true;
				} else {
					String displayLower = display.toLowerCase();
					if (concept.getPt().getTerm().toLowerCase().equals(displayLower)) {
						result = true;
					} else {
						for (Description d : concept.getActiveDescriptions()) {
							if (d.getTerm().toLowerCase().equals(displayLower)) {
								message = "Display term is acceptable, but not the preferred synonym in the language/dialect specified.";
								result = true;
								break;
							}
						}
						if (!result) {
							message = "Code exists, but the display term is not recognised.";
						}
					}
				}
				displayOut = concept.getPt().getTerm();
			} else {
				message = "The code was not found in the specified code system.";
				if (conceptAndSystemResult.message() != null) {
					message = conceptAndSystemResult.message();
				}
			}
			Parameters parameters = new Parameters();
			parameters.addParameter("result", result);
			if (concept != null) {
				parameters.addParameter("inactive", !concept.isActive());
			}
			if (message != null) {
				parameters.addParameter("message", message);
			}
			if (displayOut != null) {
				parameters.addParameter("display", displayOut);
			}
			parameters.addParameter("system", codeSystemVersion.getUrl());
			parameters.addParameter("version", codeSystemVersion.getVersion());
			return parameters;
		} else if (codeSystemParams.isUcum()) {
			return ucumCodeValidationService.validateCode(code, codeSystemParams.getCodeSystem());
		}
		else {
			FHIRCodeSystemVersion codeSystemVersion = null;
			try {
				codeSystemVersion = fhirCodeSystemService.findCodeSystemVersionOrThrow(codeSystemParams);
			} catch(SnowstormFHIRServerResponseException e) {
				if (isSupplementAsCodeSystemException(e)){
					Parameters parameters = new Parameters();
					parameters.addParameter("code", new CodeType(code));
					parameters.addParameter(new Parameters.ParametersParameterComponent(new StringType("issues")).setResource(e.getOperationOutcome()));
					parameters.addParameter("message",e.getMessage());
					parameters.addParameter("result",false);
					parameters.addParameter("system",new UriType(codeSystemParams.getCodeSystem()));
					return parameters;
				} else if(e.getOperationOutcome().getIssue().stream().anyMatch(i -> OperationOutcome.IssueType.NOTFOUND.equals(i.getCode()) && i.getLocation().stream().anyMatch(location -> "Coding.system".equals(location.toString())))){
					Parameters parameters = new Parameters();
					parameters.addParameter("code", new CodeType(code));
					String text = format("A definition for CodeSystem '%s' could not be found, so the code cannot be validated", codeSystemParams.getCodeSystem());
					OperationOutcome operationOutcome = e.getOperationOutcome();
					OperationOutcome.OperationOutcomeIssueComponent issue = operationOutcome.getIssue().get(0);
					issue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
					issue.getDetails().setText(text);
					operationOutcome.setIssue(List.of(issue));
					parameters.addParameter(new Parameters.ParametersParameterComponent(new StringType("issues")).setResource(operationOutcome));
					parameters.addParameter("message", text);
					parameters.addParameter("result",true);
					parameters.addParameter("system",new UriType(codeSystemParams.getCodeSystem()));
					return parameters;
				} else {
					throw e;
				}


			}
			FHIRConcept concept = fhirConceptService.findConcept(codeSystemVersion, code);

			if (concept != null) {
				boolean displayValidOrNull = display == null ||
						display.equals(concept.getDisplay()) ||
						concept.getDesignations().stream().anyMatch(designation -> display.equals(designation.getValue()));

				return pMapper.validateCodeResponse(concept, displayValidOrNull, codeSystemVersion);
			} else {
				return pMapper.resultFalse(code, codeSystemVersion);
			}
		}
	}

	private static boolean isSupplementAsCodeSystemException(SnowstormFHIRServerResponseException e) {
		return !Optional.ofNullable(Optional.ofNullable(Optional.ofNullable(e.getOperationOutcome()).orElse(new OperationOutcome()).getIssue()).orElse(Collections.emptyList()).stream().findFirst().orElse(new OperationOutcome.OperationOutcomeIssueComponent()).getExtension()).orElse(Collections.emptyList()).stream().filter(i -> "http://hl7.org/fhir/StructureDefinition/operationoutcome-message-id".equals(i.getUrl()) && "CODESYSTEM_CS_NO_SUPPLEMENT".equals(i.getValue().primitiveValue())).toList().isEmpty();
	}

	@Operation(name="$subsumes", idempotent=true)
	public Parameters subsumesInstance(
			@IdParam IdType id,
			HttpServletRequest request,
			HttpServletResponse response,
			@OperationParam(name="codeA") CodeType codeA,
			@OperationParam(name="codeB") CodeType codeB,
			@OperationParam(name="system") UriType system,
			@OperationParam(name="version") StringType version,
			@OperationParam(name="codingA") Coding codingA,
			@OperationParam(name="codingB") Coding codingB) {

		return subsumes(id, system, version, codeA, codeB, codingA, codingB);
	}

	@Operation(name="$subsumes", idempotent=true)
	public Parameters subsumesImplicit(
			HttpServletRequest request,
			HttpServletResponse response,
			@OperationParam(name="codeA") CodeType codeA,
			@OperationParam(name="codeB") CodeType codeB,
			@OperationParam(name="system") UriType system,
			@OperationParam(name="version") StringType version,
			@OperationParam(name="codingA") Coding codingA,
			@OperationParam(name="codingB") Coding codingB) {

		return subsumes(null, system, version, codeA, codeB, codingA, codingB);
	}

	@Operation(name="$upload-external-code-system")
	public IBaseParameters uploadExternalCodeSystem(
			HttpServletRequest theServletRequest,
			@OperationParam(name= PARAM_SYSTEM, min = 1, typeName = "uri") IPrimitiveType<String> theCodeSystemUrl,
			@OperationParam(name= PARAM_FILE, min = 1, max = OperationParam.MAX_UNLIMITED, typeName = "attachment") List<ICompositeType> theFiles,
			RequestDetails theRequestDetails) {

		FHIRHelper.readOnlyCheck(readOnlyMode);

		if (theCodeSystemUrl.getValueAsString().startsWith(ITermLoaderSvc.SCT_URI)) {
			throw exception("Uploading a SNOMED-CT code system using the FHIR API is not supported. " +
					"Please use the Snowstorm native API to manage SNOMED-CT code systems.", IssueType.NOTSUPPORTED, 400);
		}

		TerminologyUploaderProvider uploaderProvider = new TerminologyUploaderProvider(fhirContext,
				TermLoaderSvcImpl.withoutProxyCheck(new TermDeferredStorageSvc(), termCodeSystemStorage));

		return uploaderProvider.uploadSnapshot(theServletRequest, theCodeSystemUrl, theFiles, theRequestDetails);
	}

	private Parameters subsumes(IdType id, UriType system, StringType version, CodeType codeAParam, CodeType codeBParam, Coding codingA, Coding codingB) {
		// "The system parameter is required unless the operation is invoked on an instance of a code system resource." (https://www.hl7.org/fhir/codesystem-operation-subsumes.html)
		if (id == null && system == null) {
			throw exception("One of id or system parameters must be supplied for the $subsumes operation.", IssueType.INVALID, 400);
		}

		FHIRCodeSystemVersionParams codeSystemParams = getCodeSystemVersionParams(id, system, version, null);
		// Pick a code system version. A specific version of SNOMED is selected. If a version is given in the version or coding params that will be used.
		FHIRCodeSystemVersion codeSystemVersion = fhirCodeSystemService.findCodeSystemVersionOrThrow(codeSystemParams);// Performs any id / system-version crosscheck

		requireExactlyOneOf("codeA", codeAParam, "codingA", codingA);
		requireExactlyOneOf("codeB", codeBParam, "codingB", codingB);

		// Validate that codings are null or match given system
		fhirHelper.notSupportedSubsumesAcrossCodeSystemVersions(codeSystemVersion, codingA);
		fhirHelper.notSupportedSubsumesAcrossCodeSystemVersions(codeSystemVersion, codingB);

		String codeA = fhirHelper.recoverCode(codeAParam, codingA);
		String codeB = fhirHelper.recoverCode(codeBParam, codingB);
		SubsumesResult subsumesResult;
		if (isPostcoordinatedSnomed(codeA, codeSystemParams) || isPostcoordinatedSnomed(codeB, codeSystemParams)) {
			try {
				subsumesResult = fhirCodeSystemService.subsumeTestForSnomedPostcoordination(codeA, codeB, codeSystemVersion);
			} catch (ExpressionValidationException e) {
				Parameters parameters = new Parameters();
				parameters.addParameter("outcome", SubsumesResult.not_subsumed.getText());
				parameters.addParameter("message", e.getMessage());
				return parameters;
			}
		} else {
			subsumesResult = testSubsumesPrecoordinated(codeSystemVersion, codeA, codeB);
		}
		return pMapper.singleOutValue("outcome", subsumesResult.getText(), codeSystemVersion);
	}

	private SubsumesResult testSubsumesPrecoordinated(FHIRCodeSystemVersion codeSystemVersion, String codeA, String codeB) {
		if (codeA.equals(codeB) && fhirCodeSystemService.conceptExistsOrThrow(codeA, codeSystemVersion)) {
			return SubsumesResult.equivalent;
		}

		// Test for A subsumes B, then B subsumes A
		if (graphService.subsumes(codeA, codeB, codeSystemVersion)) {
			return SubsumesResult.subsumes;
		} else if (graphService.subsumes(codeB, codeA, codeSystemVersion)) {
			return SubsumesResult.subsumed_by;
		}

		fhirCodeSystemService.conceptExistsOrThrow(codeA, codeSystemVersion);
		fhirCodeSystemService.conceptExistsOrThrow(codeB, codeSystemVersion);
		return SubsumesResult.not_subsumed;
	}

	@Override
	public Class<? extends IBaseResource> getResourceType() {
		return CodeSystem.class;
	}
	
}
