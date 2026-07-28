package com.apicatalog.dto;

import com.apicatalog.model.ExtractedApi;

import java.util.List;

public class SubmitResponse {

    private String name;
    private String url;
    private String hostUrl;
    private String framework;
    private boolean supported;
    private List<ExtractedApi> apis;
    private String commitSha;
    /** True when llm.enabled=true — surfaces AI-off state to the frontend. */
    private boolean llmEnrichmentEnabled;
    /** True when a host URL was provided — surfaces Test-Request-unavailable state. */
    private boolean testRequestAvailable;
    /** Distinct API groups (tags or controller names) across the extraction run. */
    private List<String> groups;

    public SubmitResponse(String name, String url, String hostUrl, String framework,
                          boolean supported, List<ExtractedApi> apis, String commitSha,
                          boolean llmEnrichmentEnabled, boolean testRequestAvailable,
                          List<String> groups) {
        this.name = name;
        this.url = url;
        this.hostUrl = hostUrl;
        this.framework = framework;
        this.supported = supported;
        this.apis = apis;
        this.commitSha = commitSha;
        this.llmEnrichmentEnabled = llmEnrichmentEnabled;
        this.testRequestAvailable = testRequestAvailable;
        this.groups = groups;
    }

    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getHostUrl() { return hostUrl; }
    public String getFramework() { return framework; }
    public boolean isSupported() { return supported; }
    public List<ExtractedApi> getApis() { return apis; }
    public String getCommitSha() { return commitSha; }
    public boolean isLlmEnrichmentEnabled() { return llmEnrichmentEnabled; }
    public boolean isTestRequestAvailable() { return testRequestAvailable; }
    public List<String> getGroups() { return groups; }
}
