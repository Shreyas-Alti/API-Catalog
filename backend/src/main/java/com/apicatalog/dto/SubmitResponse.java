package com.apicatalog.dto;

import com.apicatalog.model.ExtractedApi;

import java.util.List;

public class SubmitResponse {

    private String name;
    private String url;
    private String framework;
    private boolean supported;
    private List<ExtractedApi> apis;

    public SubmitResponse(String name, String url, String framework, boolean supported, List<ExtractedApi> apis) {
        this.name = name;
        this.url = url;
        this.framework = framework;
        this.supported = supported;
        this.apis = apis;
    }

    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getFramework() { return framework; }
    public boolean isSupported() { return supported; }
    public List<ExtractedApi> getApis() { return apis; }
}
