package com.apicatalog.model;

import java.util.List;

public class ExtractedApi {
    private String method;
    private String path;
    private String controller;
    private String handler;
    private String description;
    private List<String> tags;
    private List<ApiParameter> parameters;
    private String requestBodyType;
    private List<ApiField> requestBodyFields;
    private String responseBodyType;
    private List<ApiField> responseBodyFields;
    private List<Integer> statusCodes;
    private String sourceFile;
    private Integer sourceLine;

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getController() { return controller; }
    public void setController(String controller) { this.controller = controller; }
    public String getHandler() { return handler; }
    public void setHandler(String handler) { this.handler = handler; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public List<ApiParameter> getParameters() { return parameters; }
    public void setParameters(List<ApiParameter> parameters) { this.parameters = parameters; }
    public String getRequestBodyType() { return requestBodyType; }
    public void setRequestBodyType(String requestBodyType) { this.requestBodyType = requestBodyType; }
    public List<ApiField> getRequestBodyFields() { return requestBodyFields; }
    public void setRequestBodyFields(List<ApiField> requestBodyFields) { this.requestBodyFields = requestBodyFields; }
    public String getResponseBodyType() { return responseBodyType; }
    public void setResponseBodyType(String responseBodyType) { this.responseBodyType = responseBodyType; }
    public List<ApiField> getResponseBodyFields() { return responseBodyFields; }
    public void setResponseBodyFields(List<ApiField> responseBodyFields) { this.responseBodyFields = responseBodyFields; }
    public List<Integer> getStatusCodes() { return statusCodes; }
    public void setStatusCodes(List<Integer> statusCodes) { this.statusCodes = statusCodes; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
    public Integer getSourceLine() { return sourceLine; }
    public void setSourceLine(Integer sourceLine) { this.sourceLine = sourceLine; }
}
