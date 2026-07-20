package com.apicatalog.dto;

import java.time.LocalDateTime;

public class RepositorySummaryDto {

    private Long id;
    private String name;
    private String url;
    private String hostUrl;
    private String framework;
    private int endpointCount;
    private LocalDateTime createdAt;

    public RepositorySummaryDto(Long id, String name, String url, String hostUrl,
                                String framework, int endpointCount, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.hostUrl = hostUrl;
        this.framework = framework;
        this.endpointCount = endpointCount;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getHostUrl() { return hostUrl; }
    public String getFramework() { return framework; }
    public int getEndpointCount() { return endpointCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
