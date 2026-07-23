package com.apicatalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private boolean enabled  = false;
    /** "anthropic" or "gemini" */
    private String  provider = "anthropic";
    private String  apiKey   = "";
    private String  model    = "claude-sonnet-4-6";
    private String  baseUrl  = "https://api.anthropic.com";

    public boolean isEnabled()  { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getApiKey()  { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel()   { return model; }
    public void setModel(String model) { this.model = model; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
