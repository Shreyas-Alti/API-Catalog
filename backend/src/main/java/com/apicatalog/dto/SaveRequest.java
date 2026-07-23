package com.apicatalog.dto;

import com.apicatalog.model.ExtractedApi;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class SaveRequest {

    @NotBlank
    private String url;

    private String hostUrl;

    @NotBlank
    private String name;

    @NotBlank
    private String framework;

    private List<ExtractedApi> apis;

    private String commitSha;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getHostUrl() { return hostUrl; }
    public void setHostUrl(String hostUrl) { this.hostUrl = hostUrl; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFramework() { return framework; }
    public void setFramework(String framework) { this.framework = framework; }

    public List<ExtractedApi> getApis() { return apis; }
    public void setApis(List<ExtractedApi> apis) { this.apis = apis; }

    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
}
