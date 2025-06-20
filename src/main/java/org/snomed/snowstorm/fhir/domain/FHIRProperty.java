package org.snomed.snowstorm.fhir.domain;

import ca.uhn.fhir.jpa.entity.TermConceptProperty;
import ca.uhn.fhir.jpa.entity.TermConceptPropertyTypeEnum;
import org.hl7.fhir.r4.model.*;

import java.util.Arrays;

public class FHIRProperty {

	public static final String STRING_TYPE = "STRING";
	public static final String CODING_TYPE = "CODING";
	public static final String CODE_TYPE = "CODE";
	public static final String BOOLEAN_TYPE = "BOOLEAN";
	public static final String INTEGER_TYPE = "INTEGER";
	public static final String DECIMAL_TYPE = "DECIMAL";

	protected static final String[] URLS = {"http://hl7.org/fhir/StructureDefinition/itemWeight",
			"http://hl7.org/fhir/StructureDefinition/codesystem-label",
			"http://hl7.org/fhir/StructureDefinition/codesystem-conceptOrder"};

	private String code;
	private String display;
	private String value;
	private String type;
	private String systemVersionUrl;

	public FHIRProperty() {
	}

	public FHIRProperty(String code, String display, String value, String type) {
		this.code = code;
		this.display = display;
		this.value = value;
		this.type = type;
	}

	public FHIRProperty(Coding coding) {
		code = coding.getCode();
		display = coding.getDisplay();
		type = CODING_TYPE;
		if (coding.hasSystem()) {
			systemVersionUrl = coding.getSystem();
		}
	}

	public FHIRProperty(TermConceptProperty property) {
		code = property.getKey();
		display = property.getDisplay();
		value = property.getValue();
		TermConceptPropertyTypeEnum enumType = property.getType();
		type = enumType != null ? enumType.name() : CODING_TYPE;
	}

	public FHIRProperty(CodeSystem.ConceptPropertyComponent propertyComponent) {
		code = propertyComponent.getCode();
		if (propertyComponent.hasValueCoding()) {
			Coding valueCoding = propertyComponent.getValueCoding();
			value = valueCoding.getCode();
			display = valueCoding.getDisplay();
			type = CODING_TYPE;
		} else if (propertyComponent.hasValueCodeType()) {
			value = propertyComponent.getValueCodeType().getValue();
			type = CODE_TYPE;
		} else if (propertyComponent.hasValueStringType()) {
			value = propertyComponent.getValueStringType().getValue();
			type = STRING_TYPE;
		} else if (propertyComponent.hasValueBooleanType()){
			value = propertyComponent.getValueBooleanType().getValueAsString();
			type = BOOLEAN_TYPE;
		} else if (propertyComponent.hasValueIntegerType()){
			value = propertyComponent.getValueIntegerType().getValueAsString();
			type = INTEGER_TYPE;
		} else if (propertyComponent.hasValueDecimalType()){
			value = propertyComponent.getValueDecimalType().getValueAsString();
			type = DECIMAL_TYPE;
		}
	}

	static String typeToFHIRPropertyType(Type value) {
		String fhirPropertyType;
		if (value instanceof CodeType) {
			fhirPropertyType = CODE_TYPE;
		} else if (value instanceof StringType){
			fhirPropertyType = STRING_TYPE;
		} else if (value instanceof Coding) {
			fhirPropertyType = CODING_TYPE;
		} else if (value instanceof BooleanType) {
			fhirPropertyType = BOOLEAN_TYPE;
		} else if (value instanceof IntegerType) {
			fhirPropertyType = INTEGER_TYPE;
		} else if (value instanceof DecimalType) {
			fhirPropertyType = DECIMAL_TYPE;
		}else {
			throw new IllegalArgumentException("unknown FHIRProperty type");
		}
		return fhirPropertyType;
	}

	public Type toHapiValue(String systemVersionUrl) {
		if (STRING_TYPE.equals(type)) {
			return new StringType(value);
		} else if (CODE_TYPE.equals(type)) {
			return new CodeType(value);
		} else if (CODING_TYPE.equals(type)) {
			return new Coding(systemVersionUrl, value, display);
		} else if (BOOLEAN_TYPE.equals(type)) {
			return new BooleanType(value);
		} else if (INTEGER_TYPE.equals(type)) {
			return new IntegerType(value);
		}else if (DECIMAL_TYPE.equals(type)) {
			return new DecimalType(value);
		}
		return null;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getDisplay() {
		return display;
	}

	public void setDisplay(String display) {
		this.display = display;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public boolean isSpecialExtension() {
		return Arrays.asList(URLS).contains(code);
	}

	public String getSystemVersionUrl() { return systemVersionUrl; }

	public void setSystemVersionUrl(String systemVersionUrl) { this.systemVersionUrl = systemVersionUrl; }
}
