package org.littleshoot.proxy.extras.logging;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import org.littleshoot.proxy.FlowContext;

/**
 * Log field that categorizes response time into categories based on configurable thresholds.
 * Categories are determined by comparing response time against thresholds in ascending order.
 */
public class ResponseTimeCategoryField implements LogField {

  private static final String FIELD_NAME = "response_time_category";
  private static final String DESCRIPTION =
      "Category based on response time (configurable thresholds)";
  private static final List<Long> DEFAULT_THRESHOLDS = Arrays.asList(100L, 500L, 2000L);
  private static final String[] DEFAULT_CATEGORY_NAMES = {"fast", "medium", "slow", "very_slow"};

  private final List<Long> thresholds;
  private final String[] categoryNames;

  /** Creates a ResponseTimeCategoryField with default thresholds (100, 500, 2000 ms). */
  public ResponseTimeCategoryField() {
    this(DEFAULT_THRESHOLDS);
  }

  /**
   * Creates a ResponseTimeCategoryField with custom thresholds.
   *
   * @param thresholds list of thresholds in milliseconds (ascending order). E.g., [100, 500, 2000]
   *     produces 4 categories: fast (&lt;100), medium (100-499), slow (500-1999), very_slow
   *     (&gt;=2000)
   */
  public ResponseTimeCategoryField(List<Long> thresholds) {
    this.thresholds = validateThresholds(thresholds);
    this.categoryNames = generateCategoryNames(this.thresholds.size());
  }

  @Override
  public String getName() {
    return FIELD_NAME;
  }

  @Override
  public String getDescription() {
    return DESCRIPTION;
  }

  @Override
  public String extractValue(FlowContext flowContext, HttpRequest request, HttpResponse response) {
    Long httpRequestProcessingTime = flowContext.getTimingData("http_request_processing_time_ms");
    return httpRequestProcessingTime != null
        ? categorizeResponseTime(httpRequestProcessingTime)
        : "-";
  }

  /**
   * Gets the configured thresholds.
   *
   * @return the list of thresholds in milliseconds
   */
  public List<Long> getThresholds() {
    return thresholds;
  }

  /**
   * Gets the category names based on the number of thresholds.
   *
   * @return the array of category names
   */
  public String[] getCategoryNames() {
    return categoryNames.clone();
  }

  private String categorizeResponseTime(long duration) {
    for (int i = 0; i < thresholds.size(); i++) {
      if (duration < thresholds.get(i)) {
        return categoryNames[i];
      }
    }
    return categoryNames[thresholds.size()];
  }

  private List<Long> validateThresholds(List<Long> thresholds) {
    if (thresholds == null || thresholds.isEmpty()) {
      throw new IllegalArgumentException("Thresholds cannot be null or empty");
    }
    if (thresholds.size() > 4) {
      throw new IllegalArgumentException("Maximum 4 thresholds allowed (produces 5 categories)");
    }
    for (int i = 0; i < thresholds.size(); i++) {
      Long threshold = thresholds.get(i);
      if (threshold == null || threshold < 0) {
        throw new IllegalArgumentException("Thresholds must be non-null and non-negative");
      }
      if (i > 0 && threshold <= thresholds.get(i - 1)) {
        throw new IllegalArgumentException("Thresholds must be in ascending order");
      }
    }
    return thresholds;
  }

  private String[] generateCategoryNames(int thresholdCount) {
    String[] names = new String[thresholdCount + 1];
    if (thresholdCount <= DEFAULT_CATEGORY_NAMES.length - 1) {
      System.arraycopy(DEFAULT_CATEGORY_NAMES, 0, names, 0, thresholdCount + 1);
    } else {
      for (int i = 0; i <= thresholdCount; i++) {
        names[i] = "category_" + i;
      }
    }
    return names;
  }

  /**
   * Creates a builder for ResponseTimeCategoryField.
   *
   * @return a new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder class for ResponseTimeCategoryField. */
  public static class Builder {
    private List<Long> thresholds = DEFAULT_THRESHOLDS;

    /**
     * Sets the thresholds for categorization.
     *
     * @param thresholds list of thresholds in milliseconds
     * @return this builder for chaining
     */
    public Builder thresholds(List<Long> thresholds) {
      this.thresholds = thresholds;
      return this;
    }

    /**
     * Sets the thresholds from a comma-separated string.
     *
     * @param thresholdsString comma-separated threshold values (e.g., "100,500,2000")
     * @return this builder for chaining
     */
    public Builder thresholdsFromString(String thresholdsString) {
      if (thresholdsString == null || thresholdsString.trim().isEmpty()) {
        return this;
      }
      String[] parts = thresholdsString.split(",");
      List<Long> parsedThresholds = new java.util.ArrayList<>();
      for (String part : parts) {
        try {
          parsedThresholds.add(Long.parseLong(part.trim()));
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Invalid threshold value: " + part);
        }
      }
      this.thresholds = parsedThresholds;
      return this;
    }

    /**
     * Builds the ResponseTimeCategoryField.
     *
     * @return the constructed field
     */
    public ResponseTimeCategoryField build() {
      return new ResponseTimeCategoryField(thresholds);
    }
  }
}
