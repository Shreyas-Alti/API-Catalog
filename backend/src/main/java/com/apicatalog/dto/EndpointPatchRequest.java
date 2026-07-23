package com.apicatalog.dto;

import java.util.List;

/**
 * Partial update for a single persisted endpoint.
 * Null fields are ignored (not cleared). Send an explicit empty string to clear a text field.
 */
public class EndpointPatchRequest {

    /** Correct a wrong HTTP method (e.g. parser emitted GET when it should be POST). */
    private String method;
    /** Correct a wrong path (e.g. parser missed a prefix). */
    private String path;
    private String description;
    private String summary;
    private List<String> tags;
    private String requestBodyType;
    private String responseBodyType;

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getRequestBodyType() { return requestBodyType; }
    public void setRequestBodyType(String requestBodyType) { this.requestBodyType = requestBodyType; }

    public String getResponseBodyType() { return responseBodyType; }
    public void setResponseBodyType(String responseBodyType) { this.responseBodyType = responseBodyType; }
}
