package com.apicatalog.dto;

import java.util.List;

public class EndpointDto {

    private Long id;
    private String method;
    private String path;
    private String description;
    private String controller;
    private String handler;
    private List<String> parameters;
    private String requestBody;
    private String responseBody;
    private List<Integer> statusCodes;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getController() { return controller; }
    public void setController(String controller) { this.controller = controller; }

    public String getHandler() { return handler; }
    public void setHandler(String handler) { this.handler = handler; }

    public List<String> getParameters() { return parameters; }
    public void setParameters(List<String> parameters) { this.parameters = parameters; }

    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public List<Integer> getStatusCodes() { return statusCodes; }
    public void setStatusCodes(List<Integer> statusCodes) { this.statusCodes = statusCodes; }
}
