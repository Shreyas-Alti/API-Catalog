package com.apicatalog.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.List;

@Entity
@Table(name = "api_endpoints")
public class ApiEndpoint {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @Column(nullable = false, length = 10) private String method;
    @Column(nullable = false) private String path;
    @Column(columnDefinition = "TEXT") private String description;
    @Column private String controller;
    @Column private String handler;

    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")
    private List<ApiParameter> parameters;

    @Column(name = "request_body_type") private String requestBodyType;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "request_body_fields", columnDefinition = "jsonb")
    private List<ApiField> requestBodyFields;

    @Column(name = "response_body_type") private String responseBodyType;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_body_fields", columnDefinition = "jsonb")
    private List<ApiField> responseBodyFields;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "status_codes", columnDefinition = "jsonb")
    private List<Integer> statusCodes;

    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")
    private List<String> tags;

    @Column(name = "source_file") private String sourceFile;
    @Column(name = "source_line") private Integer sourceLine;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Repository getRepository() { return repository; }
    public void setRepository(Repository repository) { this.repository = repository; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getController() { return controller; }
    public void setController(String controller) { this.controller = controller; }
    public String getHandler() { return handler; }
    public void setHandler(String handler) { this.handler = handler; }
    public List<ApiParameter> getParameters() { return parameters; }
    public void setParameters(List<ApiParameter> parameters) { this.parameters = parameters; }
    public String getRequestBodyType() { return requestBodyType; }
    public void setRequestBodyType(String requestBodyType) { this.requestBodyType = requestBodyType; }
    public List<ApiField> getRequestBodyFields() { return requestBodyFields; }
    public void setRequestBodyFields(List<ApiField> requestBodyFields) { this.requestBodyFields = requestBodyFields; }
    public String getResponseBodyType() { return responseBodyType; }
    public void setResponseBodyType(String responseBodyType) { this.responseBodyType = responseBodyType; }
    public List<ApiField> getResponseBodyFields() { return responseBodyFields; }
    public void setResponseBodyFields(List<ApiField> responseBodyFields) { this.responseBodyFields = responseBodyFields; }
    public List<Integer> getStatusCodes() { return statusCodes; }
    public void setStatusCodes(List<Integer> statusCodes) { this.statusCodes = statusCodes; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
    public Integer getSourceLine() { return sourceLine; }
    public void setSourceLine(Integer sourceLine) { this.sourceLine = sourceLine; }
}
