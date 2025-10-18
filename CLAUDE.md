# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Snowstorm-X** is a SNOMED CT terminology server built on Spring Boot 3.2.11 with Elasticsearch 8.11.1 as the persistence layer. It provides both HL7 FHIR R4 and specialist SNOMED CT APIs for querying and authoring medical terminology content. This is a beta version with implementation-focused features not yet in the main Snowstorm project.

**Tech Stack**: Java 17, Spring Boot, Elasticsearch, ElasticVC (Git-like versioning), HAPI FHIR, Drools validation engine, Maven

## Build & Development Commands

### Building
```bash
# Clean build (requires Docker running for tests)
mvn clean package

# Build without running tests
mvn clean package -DskipTests

# Run tests only
mvn test

# Run a single test class
mvn test -Dtest=ConceptServiceTest

# Run a single test method
mvn test -Dtest=ConceptServiceTest#testCreateConcept
```

### Running Locally
```bash
# Start Elasticsearch 8.11.1 first (required)
./bin/elasticsearch

# Run Snowstorm in read-only mode (typical development)
java -Xms2g -Xmx4g -jar target/snowstorm*.jar --snowstorm.rest-api.readonly=true

# Run with Spring Boot Maven plugin
mvn spring-boot:run

# Import RF2 snapshot and exit (initial data load)
java -Xms2g -Xmx4g -jar target/snowstorm*.jar \
  --delete-indices \
  --import=/path/to/SnomedCT_InternationalRF2_*.zip \
  --exit
```

### Docker
```bash
# Build Docker image using Jib (multi-architecture: arm64, amd64)
mvn jib:dockerBuild

# Build for specific architecture
mvn jib:dockerBuild -Pdocker-amd64
mvn jib:dockerBuild -Pdocker-arm64

# Run with Docker Compose
docker-compose up
```

### API Access
- Swagger UI: http://localhost:8080
- FHIR API: http://localhost:8080/fhir
- Actuator health: http://localhost:8080/actuator/health

## Architecture Overview

### Package Structure
```
org.snomed.snowstorm/
├── config/          # Spring configuration (Elasticsearch, FHIR, Security, JMS)
├── core/            # Core domain models and services
│   ├── data/        # Domain entities (Concept, Description, Relationship)
│   ├── rf2/         # RF2 import/export handling
│   └── util/        # Utilities
├── rest/            # REST API controllers (specialist SNOMED CT API)
├── fhir/            # FHIR R4 implementation (HAPI FHIR providers)
├── ecl/             # ECL query parsing and execution
├── validation/      # Drools-based concept validation
├── mrcm/            # Machine Readable Concept Model
└── SnowstormApplication.java
```

### Key Architectural Concepts

**ElasticVC Branching**: Git-like version control on Elasticsearch
- Branches use hierarchical paths: `MAIN`, `MAIN/PROJECT-1/TASK-2`
- All services operate within branch contexts via `BranchCriteria`
- Enables time-travel queries and branch merging

**Domain Model**:
- `Concept`: SNOMED CT concepts with definitions
- `Description`: Terms/synonyms for concepts
- `Relationship`: Hierarchical and associative relationships
- `ReferenceSetMember`: Reference set membership records
- `CodeSystem`: Edition/extension metadata (e.g., International Edition, national editions)
- `CodeSystemVersion`: Versioned snapshots with effective dates

**Service Layers**:
- Controllers (REST/FHIR) → Services → Repositories
- `QueryService`: High-level queries combining text search + ECL
- `ConceptService`: Core CRUD operations with branch support
- `BranchService`: ElasticVC integration for branch lifecycle
- `ImportService`: RF2 import orchestration
- `CodeSystemService`: Code system and version management

**Data Flow**:
1. REST/FHIR request → Controller → Service layer
2. Service builds Elasticsearch query with `BranchCriteria` (scopes to specific branch/version)
3. Repository executes native query against Elasticsearch
4. Results mapped to domain objects → JSON response

### Important Dependencies
- `io.kaicode:elasticvc` - Git-like branching and versioning on Elasticsearch
- `org.snomed.otf:snomed-boot` - RF2 format parsing
- `org.snomed.languages:snomed-ecl-parser` - ECL v2.0 query parsing
- `org.snomed.owl-toolkit` - OWL axiom handling
- `ca.uhn.hapi.fhir` - FHIR R4 server implementation
- `org.ihtsdo.drools:snomed-drools-engine` - Validation rules

## Development Guidelines

### Testing Requirements
- **Docker must be running** - Tests use TestContainers to spin up Elasticsearch containers
- Integration tests extend `AbstractTest` which provides test fixtures
- Test classes follow naming: `*Test.java` for unit tests, `*IntegrationTest.java` for integration tests
- Common test patterns available in `src/test/java/org/snomed/snowstorm/AbstractTest.java`

### Working with Branches
- All service operations require a branch path parameter (e.g., `"MAIN"`)
- Use `BranchService` to create/manage branches
- Use `VersionControlHelper` for branch metadata operations
- Branch paths are hierarchical and case-sensitive

### RF2 Import/Export
- Import via `ImportService.createJob()` + `ImportService.importArchive()`
- Two types: `RF2Type.SNAPSHOT` (single version) or `RF2Type.FULL` (complete history)
- Imports are async operations - check job status via import ID
- Always create a `CodeSystemVersion` after import for versioned releases

### ECL Queries
- Use `ECLQueryService` to parse and execute Expression Constraint Language queries
- ECL is the standard for SNOMED CT subsumption queries
- Examples: `< 73211009` (descendants of diabetes), `<< 404684003` (self + descendants)

### FHIR Integration
- FHIR providers in `fhir/` package implement HAPI FHIR resource providers
- Map between SNOMED domain objects and FHIR resources via `FHIRCodeSystemService`
- Supported operations: `$lookup`, `$validate-code`, `$expand`, `$subsumes`

### Elasticsearch Considerations
- Elasticsearch 8.11.1 is the tested version (see `pom.xml` line 25-26)
- Indices created per component type: concepts, descriptions, relationships, refset_members
- Custom analyzers for language-specific term folding
- Use `NativeQuery` builders for type-safe query construction
- Pagination via search-after for large result sets

### Security & Configuration
- Spring Security for authentication/authorization
- Configuration in `src/main/resources/application.properties`
- Environment variables for Elasticsearch connection
- Branch-level permissions via `PermissionService`

### Java 17 Compatibility
- Requires `--add-opens` JVM flags for OWL toolkit and Spring Data ES
- Already configured in `pom.xml` maven-surefire-plugin and Jib container config
- See lines 382, 402 in `pom.xml`

## Common Development Tasks

### Adding a New REST Endpoint
1. Create/update controller in `rest/` package
2. Inject required service(s) via `@Autowired`
3. Use `@PreAuthorize` for security if needed
4. Accept branch path as parameter or use `BranchPathUriRewrite`
5. Return appropriate DTO/view objects for JSON serialization

### Adding a New SNOMED Component Field
1. Update domain entity in `core/data/domain/`
2. Update Elasticsearch mapping in corresponding configuration class
3. Rebuild indices or use `--delete-indices` flag on startup
4. Update RF2 import logic in `core/rf2/` if field comes from RF2

### Modifying Validation Rules
1. Drools rules in external `snomed-drools-rules` directory
2. Rules loaded dynamically via `DroolsValidationService`
3. Test validation via `ConceptService.createUpdate()` or dedicated validation endpoint

### Creating a New Code System/Extension
1. Use `CodeSystemService.createCodeSystem()`
2. Specify branch path (e.g., `MAIN/MY-EXTENSION`)
3. Set `dependantVersionEffectiveTime` to link to parent version
4. Import RF2 content onto the extension branch
5. Create versions via `CodeSystemVersionService.createVersion()`

## Notes for AI Assistants

- **Branch context is critical** - Almost all operations require a branch path. Default to `"MAIN"` if unclear.
- **ElasticVC adds complexity** - All queries must include `BranchCriteria` to scope to the correct branch version.
- **Async operations** - Imports, merges, and large operations are async. Always provide job IDs for status checking.
- **Test with care** - Integration tests spin up real Elasticsearch containers. Ensure Docker is running.
- **SNOMED CT specifics** - This is medical terminology software. Concepts have IDs (SCTIDs), relationships are typed, and language matters.
- **Don't modify indices directly** - Use service layer methods that handle ElasticVC versioning correctly.
- **Read-only mode** - Default to `--snowstorm.rest-api.readonly=true` for development unless authoring/importing.
