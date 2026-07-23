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

    public SubmitResponse(String name, String url, String hostUrl, String framework,
                          boolean supported, List<ExtractedApi> apis, String commitSha) {
        this.name = name;
        this.url = url;
        this.hostUrl = hostUrl;
        this.framework = framework;
        this.supported = supported;
        this.apis = apis;
        this.commitSha = commitSha;
    }

    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getHostUrl() { return hostUrl; }
    public String getFramework() { return framework; }
    public boolean isSupported() { return supported; }
    public List<ExtractedApi> getApis() { return apis; }
    public String getCommitSha() { return commitSha; }
}
