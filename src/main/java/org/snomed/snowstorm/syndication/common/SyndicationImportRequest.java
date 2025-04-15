package org.snomed.snowstorm.syndication.common;

/**
     * @param terminologyName       The terminology name: snomed, loinc or hl7
     * @param version               The terminology version to import. The possible values for each terminology are enumerated below.
     *                              LOINC: latest, local, 2.80, 2.79, 2.78, ...
     *                              Hl7: latest, local, 6.2.0, 6.1.0, ...
     *                              Snomed: http ://snomed.info/sct/11000172109/, local, http ://snomed.info/sct/11000172109/version/20250315, ...
     * @param extensionName         specific to Snomed, e.g. "BE"
     * @param syndicationSecret     The syndication secret (must match with the one in the environment variable)
 */
public record SyndicationImportRequest(String terminologyName, String version, String extensionName, String syndicationSecret) {}
