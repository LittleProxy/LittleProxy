package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import org.junit.jupiter.api.Test;

/** Tests for HttpFiltersSource interface. */
class HttpFiltersSourceTest {

  @Test
  void testInterfaceDefinition() {
    assertThat(HttpFiltersSource.class).isInterface();
  }

  @Test
  void testHasFilterRequestMethod() throws NoSuchMethodException {
    assertThat(
            HttpFiltersSource.class.getMethod(
                "filterRequest", HttpRequest.class, ChannelHandlerContext.class))
        .isNotNull();
  }

  @Test
  void testHasGetMaximumRequestBufferSizeInBytesMethod() throws NoSuchMethodException {
    assertThat(HttpFiltersSource.class.getMethod("getMaximumRequestBufferSizeInBytes")).isNotNull();
    assertThat(
            HttpFiltersSource.class.getMethod("getMaximumRequestBufferSizeInBytes").getReturnType())
        .isEqualTo(int.class);
  }

  @Test
  void testHasGetMaximumResponseBufferSizeInBytesMethod() throws NoSuchMethodException {
    assertThat(HttpFiltersSource.class.getMethod("getMaximumResponseBufferSizeInBytes"))
        .isNotNull();
    assertThat(
            HttpFiltersSource.class
                .getMethod("getMaximumResponseBufferSizeInBytes")
                .getReturnType())
        .isEqualTo(int.class);
  }

  @Test
  void testFilterRequestReturnType() throws NoSuchMethodException {
    assertThat(
            HttpFiltersSource.class
                .getMethod("filterRequest", HttpRequest.class, ChannelHandlerContext.class)
                .getReturnType())
        .isEqualTo(HttpFilters.class);
  }
}
