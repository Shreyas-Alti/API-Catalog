package com.apicatalog.model;

import java.util.List;

/**
 * A single parameter extracted from an API endpoint method.
 */
public class ApiParameter {

    private String name;
    private String type;
    /** PATH | QUERY | HEADER | BODY | COOKIE */
    private String location;
    private boolean required;
    private List<String> validations;
    private String description;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public List<String> getValidations() { return validations; }
    public void setValidations(List<String> validations) { this.validations = validations; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
