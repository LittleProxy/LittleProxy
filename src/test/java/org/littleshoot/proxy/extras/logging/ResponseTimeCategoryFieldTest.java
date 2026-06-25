package org.littleshoot.proxy.extras.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.FlowContext;

class ResponseTimeCategoryFieldTest {

  private FlowContext flowContext;
  private HttpRequest request;
  private HttpResponse response;

  @BeforeEach
  void setUp() {
    flowContext = mock(FlowContext.class);
    request = mock(HttpRequest.class);
    response = mock(HttpResponse.class);
  }

  @Test
  void testDefaultThresholds() {
    ResponseTimeCategoryField field = new ResponseTimeCategoryField();
    List<Long> thresholds = field.getThresholds();
    assertThat(thresholds).containsExactly(100L, 500L, 2000L);
  }

  @Test
  void testCustomThresholds() {
    List<Long> customThresholds = Arrays.asList(50L, 200L, 1000L);
    ResponseTimeCategoryField field = new ResponseTimeCategoryField(customThresholds);
    assertThat(field.getThresholds()).containsExactly(50L, 200L, 1000L);
  }

  @Test
  void testBuilderWithThresholds() {
    ResponseTimeCategoryField field =
        ResponseTimeCategoryField.builder().thresholds(Arrays.asList(50L, 200L, 1000L)).build();
    assertThat(field.getThresholds()).containsExactly(50L, 200L, 1000L);
  }

  @Test
  void testBuilderWithThresholdsFromString() {
    ResponseTimeCategoryField field =
        ResponseTimeCategoryField.builder().thresholdsFromString("50,200,1000").build();
    assertThat(field.getThresholds()).containsExactly(50L, 200L, 1000L);
  }

  @Test
  void testDefaultCategoryNames() {
    ResponseTimeCategoryField field = new ResponseTimeCategoryField();
    String[] names = field.getCategoryNames();
    assertThat(names).containsExactly("fast", "medium", "slow", "very_slow");
  }

  @Test
  void testFastCategory() {
    ResponseTimeCategoryField field = new ResponseTimeCategoryField();
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(50L);
    String value = field.extractValue(flowContext, request, response);
    assertThat(value).isEqualTo("fast");
  }

  @Test
  void testMediumCategory() {
    ResponseTimeCategoryField field = new ResponseTimeCategoryField();
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(250L);
    String value = field.extractValue(flowContext, request, response);
    assertThat(value).isEqualTo("medium");
  }

  @Test
  void testSlowCategory() {
    ResponseTimeCategoryField field = new ResponseTimeCategoryField();
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(1000L);
    String value = field.extractValue(flowContext, request, response);
    assertThat(value).isEqualTo("slow");
  }

  @Test
  void testVerySlowCategory() {
    ResponseTimeCategoryField field = new ResponseTimeCategoryField();
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(5000L);
    String value = field.extractValue(flowContext, request, response);
    assertThat(value).isEqualTo("very_slow");
  }

  @Test
  void testNullTimingData() {
    ResponseTimeCategoryField field = new ResponseTimeCategoryField();
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(null);
    String value = field.extractValue(flowContext, request, response);
    assertThat(value).isEqualTo("-");
  }

  @Test
  void testBoundaryValues() {
    ResponseTimeCategoryField field = new ResponseTimeCategoryField();

    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(99L);
    assertThat(field.extractValue(flowContext, request, response)).isEqualTo("fast");

    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(100L);
    assertThat(field.extractValue(flowContext, request, response)).isEqualTo("medium");

    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(499L);
    assertThat(field.extractValue(flowContext, request, response)).isEqualTo("medium");

    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(500L);
    assertThat(field.extractValue(flowContext, request, response)).isEqualTo("slow");

    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(1999L);
    assertThat(field.extractValue(flowContext, request, response)).isEqualTo("slow");

    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(2000L);
    assertThat(field.extractValue(flowContext, request, response)).isEqualTo("very_slow");
  }

  @Test
  void testSingleThreshold() {
    ResponseTimeCategoryField field =
        new ResponseTimeCategoryField(Collections.singletonList(500L));
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(100L);
    assertThat(field.extractValue(flowContext, request, response)).isEqualTo("fast");

    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(500L);
    assertThat(field.extractValue(flowContext, request, response)).isEqualTo("medium");
  }

  @Test
  void testTwoThresholds() {
    ResponseTimeCategoryField field = new ResponseTimeCategoryField(Arrays.asList(100L, 500L));
    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(50L);
    assertThat(field.extractValue(flowContext, request, response)).isEqualTo("fast");

    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(200L);
    assertThat(field.extractValue(flowContext, request, response)).isEqualTo("medium");

    when(flowContext.getTimingData("http_request_processing_time_ms")).thenReturn(600L);
    assertThat(field.extractValue(flowContext, request, response)).isEqualTo("slow");
  }

  @Test
  void testInvalidNullThresholds() {
    assertThatThrownBy(() -> new ResponseTimeCategoryField(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be null or empty");
  }

  @Test
  void testInvalidEmptyThresholds() {
    assertThatThrownBy(() -> new ResponseTimeCategoryField(Collections.emptyList()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be null or empty");
  }

  @Test
  void testInvalidTooManyThresholds() {
    List<Long> tooMany = Arrays.asList(100L, 200L, 300L, 400L, 500L);
    assertThatThrownBy(() -> new ResponseTimeCategoryField(tooMany))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Maximum 4 thresholds");
  }

  @Test
  void testInvalidNegativeThreshold() {
    List<Long> negative = Arrays.asList(-100L, 500L);
    assertThatThrownBy(() -> new ResponseTimeCategoryField(negative))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be non-null and non-negative");
  }

  @Test
  void testInvalidNonAscendingThresholds() {
    List<Long> nonAscending = Arrays.asList(500L, 100L);
    assertThatThrownBy(() -> new ResponseTimeCategoryField(nonAscending))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be in ascending order");
  }

  @Test
  void testInvalidDuplicateThresholds() {
    List<Long> duplicates = Arrays.asList(100L, 100L, 500L);
    assertThatThrownBy(() -> new ResponseTimeCategoryField(duplicates))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be in ascending order");
  }

  @Test
  void testInvalidThresholdString() {
    assertThatThrownBy(
            () -> ResponseTimeCategoryField.builder().thresholdsFromString("100,abc,500").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid threshold value");
  }

  @Test
  void testFieldNameAndDescription() {
    ResponseTimeCategoryField field = new ResponseTimeCategoryField();
    assertThat(field.getName()).isEqualTo("response_time_category");
    assertThat(field.getDescription()).contains("response time");
  }

  @Test
  void testIntegrationWithLogFieldConfiguration() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder()
            .responseTimeThresholds(Arrays.asList(50L, 200L, 1000L))
            .addResponseTimeCategoryField()
            .build();

    assertThat(config.getFields()).hasSize(1);
    assertThat(config.getResponseTimeThresholds()).containsExactly(50L, 200L, 1000L);
  }

  @Test
  void testIntegrationWithDefaultThresholds() {
    LogFieldConfiguration config =
        LogFieldConfiguration.builder().addResponseTimeCategoryField().build();

    assertThat(config.getFields()).hasSize(1);
    assertThat(config.getResponseTimeThresholds()).containsExactly(100L, 500L, 2000L);
  }
}
