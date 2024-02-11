package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.littleshoot.proxy.extras.TestMitmManager;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.HashSet;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests a proxy that runs as a MITM and which is chained with
 * another proxy.
 */
@ParametersAreNonnullByDefault
public class MitmWithChainedProxyTest extends BaseChainedProxyTest {
    private final Set<HttpMethod> requestPreMethodsSeen = new HashSet<>();
    private final Set<HttpMethod> requestPostMethodsSeen = new HashSet<>();
    private final StringBuilder responsePreBody = new StringBuilder();
    private final StringBuilder responsePostBody = new StringBuilder();
    private final Set<HttpMethod> responsePreOriginalRequestMethodsSeen = new HashSet<>();
    private final Set<HttpMethod> responsePostOriginalRequestMethodsSeen = new HashSet<>();

    @Override
    protected final void setUp() {
        REQUESTS_SENT_BY_DOWNSTREAM.set(0);
        REQUESTS_RECEIVED_BY_UPSTREAM.set(0);
        TRANSPORTS_USED.clear();
        upstreamProxy = upstreamProxy().start();

        proxyServer = bootstrapProxy()
                .withPort(0)
                .withChainProxyManager(chainedProxyManager())
                .plusActivityTracker(DOWNSTREAM_TRACKER)
                .withManInTheMiddle(new TestMitmManager())
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public HttpResponse clientToProxyRequest(
                                    HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest) {
                                    requestPreMethodsSeen
                                            .add(((HttpRequest) httpObject)
                                                    .method());
                                }
                                return null;
                            }

                            @Override
                            public HttpResponse proxyToServerRequest(
                                    HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest) {
                                    requestPostMethodsSeen
                                            .add(((HttpRequest) httpObject)
                                                    .method());
                                }
                                return null;
                            }

                            @Override
                            public HttpObject serverToProxyResponse(
                                    HttpObject httpObject) {
                                if (httpObject instanceof HttpResponse) {
                                    responsePreOriginalRequestMethodsSeen
                                            .add(originalRequest.method());
                                } else if (httpObject instanceof HttpContent) {
                                    responsePreBody.append(((HttpContent) httpObject)
                                            .content().toString(UTF_8));
                                }
                                return httpObject;
                            }

                            @Override
                            public HttpObject proxyToClientResponse(
                                    HttpObject httpObject) {
                                if (httpObject instanceof HttpResponse) {
                                    responsePostOriginalRequestMethodsSeen
                                            .add(originalRequest.method());
                                } else if (httpObject instanceof HttpContent) {
                                    responsePostBody.append(((HttpContent) httpObject)
                                            .content().toString(UTF_8));
                                }
                                return httpObject;
                            }
                        };
                    }
                })
                .start();
    }

    @Override
    protected boolean isMITM() {
        return true;
    }

    @Override
    public void testSimpleGetRequest() {
        super.testSimpleGetRequest();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertMethodSeenInRequestFilters(HttpMethod.GET);
            assertMethodSeenInResponseFilters(HttpMethod.GET);
            assertResponseFromFiltersMatchesActualResponse();
        }
    }

    @Override
    public void testSimpleGetRequestOverHTTPS() {
        super.testSimpleGetRequestOverHTTPS();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertMethodSeenInRequestFilters(HttpMethod.CONNECT);
            assertMethodSeenInRequestFilters(HttpMethod.GET);
            assertMethodSeenInResponseFilters(HttpMethod.GET);
            assertResponseFromFiltersMatchesActualResponse();
        }
    }

    @Override
    public void testSimplePostRequest() {
        super.testSimplePostRequest();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertMethodSeenInRequestFilters(HttpMethod.POST);
            assertMethodSeenInResponseFilters(HttpMethod.POST);
            assertResponseFromFiltersMatchesActualResponse();
        }
    }

    @Override
    public void testSimplePostRequestOverHTTPS() {
        super.testSimplePostRequestOverHTTPS();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertMethodSeenInRequestFilters(HttpMethod.CONNECT);
            assertMethodSeenInRequestFilters(HttpMethod.POST);
            assertMethodSeenInResponseFilters(HttpMethod.POST);
            assertResponseFromFiltersMatchesActualResponse();
        }
    }

    private void assertMethodSeenInRequestFilters(HttpMethod method) {
        assertThat(requestPreMethodsSeen)
          .as("%s should have been seen in clientToProxyRequest filter", method)
          .contains(method);
        assertThat(requestPostMethodsSeen)
          .as("%s should have been seen in proxyToServerRequest filter", method)
          .contains(method);
    }

    private void assertMethodSeenInResponseFilters(HttpMethod method) {
        assertThat(responsePreOriginalRequestMethodsSeen)
          .as(method + " should have been seen as the original request's method in serverToProxyResponse filter")
            .contains(method);
        assertThat(responsePostOriginalRequestMethodsSeen)
          .as(method + " should have been seen as the original request's method in proxyToClientResponse filter")
          .contains(method);
    }

    private void assertResponseFromFiltersMatchesActualResponse() {
        assertThat(responsePreBody.toString())
          .as("Data received through HttpFilters.serverToProxyResponse should match response")
          .isEqualTo(lastResponse);
        assertThat(responsePostBody.toString())
          .as("Data received through HttpFilters.proxyToClientResponse should match response")
          .isEqualTo(lastResponse);
    }

    @Override
    protected final void tearDown() {
        upstreamProxy.abort();
    }
}
