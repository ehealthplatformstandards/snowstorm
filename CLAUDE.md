# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Snowstorm-X is a SNOMED CT terminology server built on Spring Boot 3 and Elasticsearch 8. It provides both a FHIR API and a specialized SNOMED CT API for managing healthcare terminologies. This is a beta version with implementation-focused features.

## Build and Run Commands

### Build
```bash
# Build with Maven (Docker must be running for tests)
mvn clean package

# Skip tests if needed
mvn clean package -DskipTests

# Build Docker image
mvn jib:dockerBuild
```

### Run Locally
```bash
# Start Elasticsearch first (version 8.11.1)
./bin/elasticsearch

# Start Snowstorm in read-only mode
java -Xms2g -Xmx4g -jar target/snowstorm*.jar --snowstorm.rest-api.readonly=true

# Start with data import
java -Xms2g -Xmx4g -jar target/snowstorm*.jar --delete-indices --import=<path-to-RF2-zip>
```

### Testing
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=ConceptServiceTest

# Run specific test method
mvn test -Dtest=ConceptServiceTest#testCreateConcept
```

### Docker
```bash
# Build and run with Docker Compose (if available)
docker-compose up

# Run with specific Elasticsearch URL
java -jar snowstorm.jar --elasticsearch.urls=http://localhost:9200
```

## Architecture

### Core Domain Model
The main SNOMED CT components are in `org.snomed.snowstorm.core.data.domain`:
- **Concept**: Central entity representing SNOMED CT concepts
- **Description**: Textual descriptions/terms for concepts (FSN, synonyms)
- **Relationship**: Relationships between concepts (including IS-A hierarchy)
- **ReferenceSetMember**: Members of reference sets (language, associations, etc.)
- **Axiom**: OWL axioms for formal concept definitions

### Key Services Layer
Services in `org.snomed.snowstorm.core.data.services` implement core business logic:
- **CodeSystemService**: Manages code systems (editions/extensions) and branches
- **ConceptService**: CRUD operations for concepts including authoring support
- **DescriptionService**: Manages descriptions with multi-language support
- **QueryService**: ECL (Expression Constraint Language) query execution
- **ReferenceSetMemberService**: Reference set operations
- **BranchMergeService**: Branching and merging for authoring workflows
- **SemanticIndexUpdateService**: Maintains transitive closure for ECL queries

### Branching Model
Snowstorm uses a sophisticated branching model via the ElasticVC library:
- Main branch: **MAIN** (represents International Edition base)
- Code system branches: **MAIN/{CODE_SYSTEM}** (e.g., MAIN/SNOMEDCT-BE)
- Task branches for authoring: **MAIN/{CODE_SYSTEM}/TASK-{ID}**
- Branches support versioning, merging, and conflict resolution

### REST APIs
Two distinct API layers in `org.snomed.snowstorm.rest`:
- **FHIR API** (`org.snomed.snowstorm.fhir`): Implements HL7 FHIR Terminology Module (CodeSystem, ValueSet, ConceptMap operations)
- **SNOMED API** (`org.snomed.snowstorm.rest`): Specialized endpoints for browsing, authoring, and managing SNOMED CT

### Data Storage
- **Elasticsearch 8.11.1**: Primary data store for all SNOMED CT components
- Index prefix configurable via `elasticsearch.index.prefix`
- Uses ElasticVC library for versioning on top of Elasticsearch
- Semantic index maintained for fast ECL queries

### Import/Export
- **RF2 Import** (`org.snomed.snowstorm.core.rf2.rf2import`): Imports SNOMED CT RF2 release files
- **RF2 Export** (`org.snomed.snowstorm.core.rf2.export`): Exports content as RF2
- Supports both SNAPSHOT (latest state) and FULL (all versions) imports
- Syndication service for automated imports of various terminologies

## Development Patterns

### Service Layer Pattern
Services are autowired and use repositories for data access:
```java
@Service
public class ConceptService extends ComponentService {
    @Autowired
    private ConceptRepository conceptRepository;

    public Concept find(String conceptId, String path) {
        // Implementation uses branch-aware queries
    }
}
```

### Branch-Aware Queries
All queries must specify a branch path (e.g., "MAIN", "MAIN/SNOMEDCT-BE"):
```java
// Query concepts on a specific branch
List<Concept> concepts = elasticsearchOperations.search(
    queryBuilder.withFilter(termsQuery("path", branchCriteria.getEntityBranchCriteria())),
    Concept.class
);
```

### Validation and Integrity
- Drools rules engine (`snomed-drools-rules`) for authoring validation
- IntegrityService performs constraint checks before commits
- Classification service integration for OWL reasoning

## Configuration

Key configuration properties in `application.properties`:
- **elasticsearch.urls**: Elasticsearch cluster URL(s)
- **snowstorm.rest-api.readonly**: Enable/disable authoring endpoints
- **cis.api.url**: Component Identifier Service (use "local-sequential" for local SCTID generation)
- **validation.drools.rules.path**: Path to Drools validation rules
- **codesystem.config.{SHORT_NAME}**: Individual code system configurations

## Common Development Tasks

### Adding a New Refset Type
Update `application.properties` with pattern:
```properties
refset.types.{Name}={ConceptId}|{ExportDir}|{FieldTypes}|{FieldNames}
```

### Adding a New Code System Configuration
Update `application.properties`:
```properties
codesystem.config.{SHORT_NAME}={Name}|{DefaultModule}|{CountryCode}|{Owner}
```

### Working with Branches
```java
// Create a branch
branchService.create("MAIN/SNOMEDCT-BE/TASK-123");

// Merge branch
branchMergeService.mergeBranchSync("MAIN/SNOMEDCT-BE/TASK-123", "MAIN/SNOMEDCT-BE", Collections.emptyList());
```

## Testing Infrastructure

- Uses **Testcontainers** for Elasticsearch in tests (requires Docker)
- Base test classes: `AbstractTest` provides common test setup
- Test Elasticsearch version defined in `TestConfig.ELASTIC_SEARCH_SERVER_VERSION`
- Integration tests create temporary branches for isolation

## Key Dependencies

- **Spring Boot 3.2**: Web framework and dependency injection
- **Spring Data Elasticsearch**: Elasticsearch integration
- **ElasticVC**: Version control on Elasticsearch
- **snomed-boot**: RF2 file parsing
- **snomed-owl-toolkit**: OWL axiom processing
- **snomed-ecl-parser**: ECL query parsing
- **HAPI FHIR**: FHIR API implementation
- **Drools**: Business rules engine for validation

## Java Version and Runtime

- **Java 17** required
- JVM flags needed: `--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED`
- These flags are configured in pom.xml for tests and runtime

## Branch Strategy

This is a fork/variant of the main Snowstorm project:
- Main development branch: **ehealth-master**
- Pull requests should target: **master**
- See git status for current branch state
