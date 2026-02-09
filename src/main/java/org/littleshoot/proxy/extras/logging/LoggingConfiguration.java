package org.littleshoot.proxy.extras.logging;

import java.util.List;

/**
 * Configuration model for logging field configuration. Used for JSON deserialization of logging
 * configuration files.
 */
public class LoggingConfiguration {

  private List<String> standardFields;
  private List<PrefixHeaderConfig> requestPrefixHeaders;
  private List<RegexHeaderConfig> requestRegexHeaders;
  private List<ExcludeHeaderConfig> requestExcludeHeaders;
  private List<PrefixHeaderConfig> responsePrefixHeaders;
  private List<RegexHeaderConfig> responseRegexHeaders;
  private List<ExcludeHeaderConfig> responseExcludeHeaders;
  private List<String> computedFields;
  private List<SingleHeaderConfig> singleHeaders;

  public List<String> getStandardFields() {
    return standardFields;
  }

  public void setStandardFields(List<String> standardFields) {
    this.standardFields = standardFields;
  }

  public List<PrefixHeaderConfig> getRequestPrefixHeaders() {
    return requestPrefixHeaders;
  }

  public void setRequestPrefixHeaders(List<PrefixHeaderConfig> requestPrefixHeaders) {
    this.requestPrefixHeaders = requestPrefixHeaders;
  }

  public List<PrefixHeaderConfig> getResponsePrefixHeaders() {
    return responsePrefixHeaders;
  }

  public void setResponsePrefixHeaders(List<PrefixHeaderConfig> responsePrefixHeaders) {
    this.responsePrefixHeaders = responsePrefixHeaders;
  }

  public List<RegexHeaderConfig> getRequestRegexHeaders() {
    return requestRegexHeaders;
  }

  public void setRequestRegexHeaders(List<RegexHeaderConfig> requestRegexHeaders) {
    this.requestRegexHeaders = requestRegexHeaders;
  }

  public List<RegexHeaderConfig> getResponseRegexHeaders() {
    return responseRegexHeaders;
  }

  public void setResponseRegexHeaders(List<RegexHeaderConfig> responseRegexHeaders) {
    this.responseRegexHeaders = responseRegexHeaders;
  }

  public List<ExcludeHeaderConfig> getRequestExcludeHeaders() {
    return requestExcludeHeaders;
  }

  public void setRequestExcludeHeaders(List<ExcludeHeaderConfig> requestExcludeHeaders) {
    this.requestExcludeHeaders = requestExcludeHeaders;
  }

  public List<ExcludeHeaderConfig> getResponseExcludeHeaders() {
    return responseExcludeHeaders;
  }

  public void setResponseExcludeHeaders(List<ExcludeHeaderConfig> responseExcludeHeaders) {
    this.responseExcludeHeaders = responseExcludeHeaders;
  }

  public List<String> getComputedFields() {
    return computedFields;
  }

  public void setComputedFields(List<String> computedFields) {
    this.computedFields = computedFields;
  }

  public List<SingleHeaderConfig> getSingleHeaders() {
    return singleHeaders;
  }

  public void setSingleHeaders(List<SingleHeaderConfig> singleHeaders) {
    this.singleHeaders = singleHeaders;
  }

  /** Configuration for prefix-based header matching. */
  public static class PrefixHeaderConfig {
    private String prefix;
    private String fieldNameTransformer;
    private String valueTransformer;

    public String getPrefix() {
      return prefix;
    }

    public void setPrefix(String prefix) {
      this.prefix = prefix;
    }

    public String getFieldNameTransformer() {
      return fieldNameTransformer;
    }

    public void setFieldNameTransformer(String fieldNameTransformer) {
      this.fieldNameTransformer = fieldNameTransformer;
    }

    public String getValueTransformer() {
      return valueTransformer;
    }

    public void setValueTransformer(String valueTransformer) {
      this.valueTransformer = valueTransformer;
    }
  }

  /** Configuration for regex-based header matching. */
  public static class RegexHeaderConfig {
    private String pattern;
    private String fieldNameTransformer;
    private String valueTransformer;

    public String getPattern() {
      return pattern;
    }

    public void setPattern(String pattern) {
      this.pattern = pattern;
    }

    public String getFieldNameTransformer() {
      return fieldNameTransformer;
    }

    public void setFieldNameTransformer(String fieldNameTransformer) {
      this.fieldNameTransformer = fieldNameTransformer;
    }

    public String getValueTransformer() {
      return valueTransformer;
    }

    public void setValueTransformer(String valueTransformer) {
      this.valueTransformer = valueTransformer;
    }
  }

  /** Configuration for exclude-based header matching. */
  public static class ExcludeHeaderConfig {
    private String pattern;
    private String fieldNameTransformer;
    private String valueTransformer;

    public String getPattern() {
      return pattern;
    }

    public void setPattern(String pattern) {
      this.pattern = pattern;
    }

    public String getFieldNameTransformer() {
      return fieldNameTransformer;
    }

    public void setFieldNameTransformer(String fieldNameTransformer) {
      this.fieldNameTransformer = fieldNameTransformer;
    }

    public String getValueTransformer() {
      return valueTransformer;
    }

    public void setValueTransformer(String valueTransformer) {
      this.valueTransformer = valueTransformer;
    }
  }

  /** Configuration for single header matching. */
  public static class SingleHeaderConfig {
    private String headerName;
    private String fieldName;
    private String valueTransformer;
    private boolean request;

    public String getHeaderName() {
      return headerName;
    }

    public void setHeaderName(String headerName) {
      this.headerName = headerName;
    }

    public String getFieldName() {
      return fieldName;
    }

    public void setFieldName(String fieldName) {
      this.fieldName = fieldName;
    }

    public String getValueTransformer() {
      return valueTransformer;
    }

    public void setValueTransformer(String valueTransformer) {
      this.valueTransformer = valueTransformer;
    }

    public boolean isRequest() {
      return request;
    }

    public void setRequest(boolean request) {
      this.request = request;
    }
  }
}
