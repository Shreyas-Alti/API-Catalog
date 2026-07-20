package com.apicatalog.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "repositories")
public class Repository {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String url;

    @Column(name = "host_url")
    private String hostUrl;

    @Column
    private String framework;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "repository", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ApiEndpoint> endpoints = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getHostUrl() { return hostUrl; }
    public void setHostUrl(String hostUrl) { this.hostUrl = hostUrl; }

    public String getFramework() { return framework; }
    public void setFramework(String framework) { this.framework = framework; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public List<ApiEndpoint> getEndpoints() { return endpoints; }
    public void setEndpoints(List<ApiEndpoint> endpoints) { this.endpoints = endpoints; }
}
