package org.littleshoot.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import org.junit.jupiter.api.Test;

class HttpFiltersSourceAdapterTest {

  @Test
  void testImplementsHttpFiltersSource() {
    assertThat(new HttpFiltersSourceAdapter()).isInstanceOf(HttpFiltersSource.class);
  }

  @Test
  void testFilterRequestWithRequestOnly() {
    HttpFiltersSourceAdapter adapter = new HttpFiltersSourceAdapter();
    HttpRequest request = mock(HttpRequest.class);

    HttpFilters result = adapter.filterRequest(request);

    assertThat(result).isNotNull();
    assertThat(result).isInstanceOf(HttpFiltersAdapter.class);
  }

  @Test
  void testFilterRequestWithRequestAndContext() {
    HttpFiltersSourceAdapter adapter = new HttpFiltersSourceAdapter();
    HttpRequest request = mock(HttpRequest.class);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

    HttpFilters result = adapter.filterRequest(request, ctx);

    assertThat(result).isNotNull();
    assertThat(result).isInstanceOf(HttpFiltersAdapter.class);
  }

  @Test
  void testGetMaximumRequestBufferSizeInBytes() {
    HttpFiltersSourceAdapter adapter = new HttpFiltersSourceAdapter();

    int size = adapter.getMaximumRequestBufferSizeInBytes();

    assertThat(size).isEqualTo(0);
  }

  @Test
  void testGetMaximumResponseBufferSizeInBytes() {
    HttpFiltersSourceAdapter adapter = new HttpFiltersSourceAdapter();

    int size = adapter.getMaximumResponseBufferSizeInBytes();

    assertThat(size).isEqualTo(0);
  }

  @Test
  void testFilterRequestDoesNotThrow() {
    HttpFiltersSourceAdapter adapter = new HttpFiltersSourceAdapter();
    HttpRequest request = mock(HttpRequest.class);
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

    assertThatCode(() -> adapter.filterRequest(request, ctx)).doesNotThrowAnyException();
  }
}
