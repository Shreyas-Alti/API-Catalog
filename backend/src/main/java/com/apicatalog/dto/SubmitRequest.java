package com.apicatalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class SubmitRequest {

    @NotBlank(message = "URL is required")
    @Pattern(regexp = "^https://.*", message = "Only HTTPS URLs are supported")
    private String url;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
