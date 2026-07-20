package com.apicatalog.model;

import java.util.List;

/**
 * A single field extracted from a request/response body DTO (one level deep).
 */
public class ApiField {

    private String name;
    private String type;
    private List<String> validations;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public List<String> getValidations() { return validations; }
    public void setValidations(List<String> validations) { this.validations = validations; }
}
