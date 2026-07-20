package com.apicatalog.dto;

import java.time.LocalDateTime;
import java.util.List;

public class RepositoryDetailDto {

    private Long id;
    private String name;
    private String url;
    private String hostUrl;
    private String framework;
    private LocalDateTime createdAt;
    private List<EndpointDto> endpoints;

    public RepositoryDetailDto(Long id, String name, String url, String hostUrl,
                               String framework, LocalDateTime createdAt, List<EndpointDto> endpoints) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.hostUrl = hostUrl;
        this.framework = framework;
        this.createdAt = createdAt;
        this.endpoints = endpoints;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getHostUrl() { return hostUrl; }
    public String getFramework() { return framework; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<EndpointDto> getEndpoints() { return endpoints; }
}
