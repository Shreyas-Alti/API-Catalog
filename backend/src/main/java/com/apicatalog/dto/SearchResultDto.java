package com.apicatalog.dto;

public class SearchResultDto {

    private Long endpointId;
    private String method;
    private String path;
    private String description;
    private String controller;
    private String handler;

    private Long repositoryId;
    private String repositoryName;
    private String repositoryUrl;
    private String framework;

    public SearchResultDto(Long endpointId, String method, String path, String description,
                           String controller, String handler,
                           Long repositoryId, String repositoryName,
                           String repositoryUrl, String framework) {
        this.endpointId = endpointId;
        this.method = method;
        this.path = path;
        this.description = description;
        this.controller = controller;
        this.handler = handler;
        this.repositoryId = repositoryId;
        this.repositoryName = repositoryName;
        this.repositoryUrl = repositoryUrl;
        this.framework = framework;
    }

    public Long getEndpointId() { return endpointId; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getDescription() { return description; }
    public String getController() { return controller; }
    public String getHandler() { return handler; }
    public Long getRepositoryId() { return repositoryId; }
    public String getRepositoryName() { return repositoryName; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public String getFramework() { return framework; }
}
