package org.littleshoot.proxy;

import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.util.Arrays;

import static org.apache.http.client.utils.HttpClientUtils.closeQuietly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.TestUtils.createProxiedHttpClient;

@Tag("slow-test")
@Timeout(15)
public final class ThrottlingTest {
    private static final int LARGE_DATA_SIZE = 200000;
    private static final long THROTTLED_READ_BYTES_PER_SECOND = 25000L;
    private static final long THROTTLED_WRITE_BYTES_PER_SECOND = 25000L;

    // throttling is not guaranteed to be exact, so allow some variation in the amount of time the call takes. since we want
    // these tests to take just a few seconds, allow significant variation. even with this large variation, if throttling
    // is broken it should take much less time than expected.
    private static final double ALLOWABLE_VARIATION = 0.30;

    private HttpProxyServer proxyServer;
    private Server writeWebServer;
    private Server readWebServer;

    private byte[] largeData;

    private int msToWriteThrottled;
    private int msToReadThrottled;

    // time to allow for an unthrottled local request
    private static final long UNTHROTTLED_REQUEST_TIME_MS = 1500;

    private int writeWebServerPort;
    private int readWebServerPort;

    @BeforeEach
    void setUp() {
        // Set up some large data
        largeData = new byte[LARGE_DATA_SIZE];
        Arrays.fill(largeData, (byte) (1 % 256));

        msToWriteThrottled = largeData.length  * 1000 / (int)THROTTLED_WRITE_BYTES_PER_SECOND;
        msToReadThrottled = largeData.length  * 1000 / (int)THROTTLED_READ_BYTES_PER_SECOND;

        writeWebServer = TestUtils.startWebServer(false);
        writeWebServerPort = TestUtils.findLocalHttpPort(writeWebServer);

        readWebServer = TestUtils.startWebServerWithResponse(false, largeData);
        readWebServerPort = TestUtils.findLocalHttpPort(readWebServer);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (proxyServer != null) {
                proxyServer.abort();
            }
        } finally {
            try {
                if (writeWebServer != null) {
                    writeWebServer.stop();
                }
            } finally {
                if (readWebServer != null) {
                    readWebServer.stop();
                }
            }
        }
    }

    @Test
    public void aWarmUpTest() throws Exception {
        // a "warm-up" test so the first test's results are not skewed due to classloading, etc. guaranteed to run
        // first with the @FixMethodOrder(MethodSorters.NAME_ASCENDING) annotation on the class.

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withThrottling(0, THROTTLED_WRITE_BYTES_PER_SECOND)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        HttpGet request = createHttpGet();
        try (CloseableHttpClient httpClient = createProxiedHttpClient(proxyPort)) {
            EntityUtils.consumeQuietly(httpClient.execute(new HttpHost("127.0.0.1", writeWebServerPort), request).getEntity());
            EntityUtils.consumeQuietly(httpClient.execute(new HttpHost("127.0.0.1", readWebServerPort), request).getEntity());
        }
    }

    @Test
    public void testThrottledWrite() throws Exception {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withThrottling(0, THROTTLED_WRITE_BYTES_PER_SECOND)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        final HttpPost request = createHttpPost();

        try (CloseableHttpClient httpClient = createProxiedHttpClient(proxyPort)) {

            long start = System.currentTimeMillis();
            org.apache.http.HttpResponse response = httpClient.execute(new HttpHost("127.0.0.1", writeWebServerPort), request);
            long finish = System.currentTimeMillis();

            assertThat(EntityUtils.toString(response.getEntity())).isEqualTo("Received " + largeData.length + " bytes\n");

            assertThat((double) (finish - start))
              .as("Expected throttled write to complete in approximately %s ms but took %s ms", msToWriteThrottled, finish - start)
              .isGreaterThan(msToWriteThrottled * (1 - ALLOWABLE_VARIATION))
              .isLessThan(msToWriteThrottled * (1 + ALLOWABLE_VARIATION));
        }
    }

    @Test
    public void testUnthrottledWrite() throws Exception {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        final HttpPost request = createHttpPost();

        try(CloseableHttpClient httpClient = createProxiedHttpClient(proxyPort)) {

            long start = System.currentTimeMillis();
            final org.apache.http.HttpResponse response = httpClient.execute(
                    new HttpHost("127.0.0.1",
                            writeWebServerPort), request);
            long finish = System.currentTimeMillis();

            assertThat(EntityUtils.toString(response.getEntity())).isEqualTo("Received " + largeData.length + " bytes\n");

            assertThat(finish - start)
              .as("Unthrottled write took %s ms, but expected to complete in %s ms", finish - start, UNTHROTTLED_REQUEST_TIME_MS)
              .isLessThan(UNTHROTTLED_REQUEST_TIME_MS);
        }
    }

    @Test
    public void testThrottledRead() throws Exception {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withThrottling(THROTTLED_READ_BYTES_PER_SECOND, 0)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        final HttpGet request = createHttpGet();

        try(CloseableHttpClient httpClient = createProxiedHttpClient(proxyPort)) {

            long start = System.currentTimeMillis();
            final org.apache.http.HttpResponse response = httpClient.execute(
                    new HttpHost("127.0.0.1",
                            readWebServerPort), request);
            byte[] readContent = new byte[LARGE_DATA_SIZE];

            int bytesRead = 0;
            while (bytesRead < largeData.length) {
                int read = response.getEntity().getContent().read(readContent, bytesRead, largeData.length - bytesRead);
                bytesRead += read;
            }

            long finish = System.currentTimeMillis();

            assertThat(bytesRead)
              .as("Expected to read %s bytes but actually read %s bytes", LARGE_DATA_SIZE, bytesRead)
              .isEqualTo(LARGE_DATA_SIZE);

            assertThat((double) (finish - start))
              .as("Expected throttled read to complete in approximately %s ms but took %s ms", msToReadThrottled, finish - start)
              .isGreaterThan(msToReadThrottled * (1 - ALLOWABLE_VARIATION))
              .isLessThan(msToReadThrottled * (1 + ALLOWABLE_VARIATION));
        }
    }

    @Test
    public void testUnthrottledRead() throws Exception {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        final HttpGet request = createHttpGet();

        try(CloseableHttpClient httpClient = createProxiedHttpClient(proxyPort)) {

            long start = System.currentTimeMillis();
            final org.apache.http.HttpResponse response = httpClient.execute(
                    new HttpHost("127.0.0.1",
                            readWebServerPort), request);

            byte[] readContent = new byte[LARGE_DATA_SIZE];
            int bytesRead = 0;
            while (bytesRead < largeData.length) {
                int read = response.getEntity().getContent().read(readContent, bytesRead, largeData.length - bytesRead);
                bytesRead += read;
            }

            long finish = System.currentTimeMillis();

            assertThat(bytesRead)
              .as("Expected to read %s bytes but actually read %s bytes", LARGE_DATA_SIZE, bytesRead)
              .isEqualTo(LARGE_DATA_SIZE);

            assertThat(finish - start)
              .as("Unthrottled read took %s ms, but expected to complete in %s ms", finish - start, UNTHROTTLED_REQUEST_TIME_MS)
              .isLessThan(UNTHROTTLED_REQUEST_TIME_MS);
        }
    }

    @Test
    @Timeout(20)
    public void testChangeThrottling() throws Exception {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withThrottling(THROTTLED_READ_BYTES_PER_SECOND, 0)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        final HttpGet request = createHttpGet();

        try(CloseableHttpClient httpClient = createProxiedHttpClient(proxyPort)) {

            long firstStart = System.currentTimeMillis();
            org.apache.http.HttpResponse response = httpClient.execute(
                    new HttpHost("127.0.0.1",
                            readWebServerPort), request);
            byte[] readContent = new byte[LARGE_DATA_SIZE];

            int bytesRead = 0;
            while (bytesRead < largeData.length) {
                int read = response.getEntity().getContent().read(readContent, bytesRead, largeData.length - bytesRead);
                bytesRead += read;
            }

            long firstFinish = System.currentTimeMillis();

            assertThat(bytesRead)
              .as("Expected to read %s bytes but actually read %s bytes", LARGE_DATA_SIZE, bytesRead)
              .isEqualTo(LARGE_DATA_SIZE);

            closeQuietly(response);

            proxyServer.setThrottle(THROTTLED_READ_BYTES_PER_SECOND * 2, 0);
            Thread.sleep(1000); // necessary for the traffic shaping to reset

            long secondStart = System.currentTimeMillis();
            response = httpClient.execute(
                    new HttpHost("127.0.0.1",
                            readWebServerPort), request);
            readContent = new byte[LARGE_DATA_SIZE];

            bytesRead = 0;
            while (bytesRead < largeData.length) {
                int read = response.getEntity().getContent().read(readContent, bytesRead, largeData.length - bytesRead);
                bytesRead += read;
            }

            long secondFinish = System.currentTimeMillis();

            assertThat(bytesRead)
              .as("Expected to read %s bytes but actually read %s bytes", LARGE_DATA_SIZE, bytesRead)
              .isEqualTo(LARGE_DATA_SIZE);

            closeQuietly(response);

            assertThat((double) (firstFinish - firstStart) / 2)
              .as("Expected second read to take approximately half as long as first throttled read. First read took %s ms but second read took %s ms",
                firstFinish - firstStart, secondFinish - secondStart)
              .isGreaterThan((secondFinish - secondStart) * (1 - ALLOWABLE_VARIATION))
              .isLessThan((secondFinish - secondStart) * (1 + ALLOWABLE_VARIATION));
        }
    }

    @Test
    public void testDisableThrottling() throws Exception {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withThrottling(THROTTLED_READ_BYTES_PER_SECOND, 0)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        final HttpGet request = createHttpGet();

        try(CloseableHttpClient httpClient = createProxiedHttpClient(proxyPort)) {

            long firstStart = System.currentTimeMillis();
            org.apache.http.HttpResponse response = httpClient.execute(
                    new HttpHost("127.0.0.1",
                            readWebServerPort), request);
            byte[] readContent = new byte[LARGE_DATA_SIZE];

            int bytesRead = 0;
            while (bytesRead < largeData.length) {
                int read = response.getEntity().getContent().read(readContent, bytesRead, largeData.length - bytesRead);
                bytesRead += read;
            }

            long firstFinish = System.currentTimeMillis();

            assertThat(bytesRead)
              .as("Expected to read %s bytes but actually read %s bytes", LARGE_DATA_SIZE, bytesRead)
              .isEqualTo(LARGE_DATA_SIZE);

            closeQuietly(response);

            proxyServer.setThrottle(0, 0);
            Thread.sleep(1000); // necessary for the traffic shaping to reset

            long secondStart = System.currentTimeMillis();
            response = httpClient.execute(
                    new HttpHost("127.0.0.1",
                            readWebServerPort), request);
            readContent = new byte[LARGE_DATA_SIZE];

            bytesRead = 0;
            while (bytesRead < largeData.length) {
                int read = response.getEntity().getContent().read(readContent, bytesRead, largeData.length - bytesRead);
                bytesRead += read;
            }

            long secondFinish = System.currentTimeMillis();

            assertThat(bytesRead)
              .as("Expected to read %s bytes but actually read %s bytes", LARGE_DATA_SIZE, bytesRead)
              .isEqualTo(LARGE_DATA_SIZE);

            closeQuietly(response);

            assertThat(secondFinish - secondStart)
              .as("Expected second read to complete within %s ms, without throttling. First read took %s ms" + ". Second read took %s ms.",
                UNTHROTTLED_REQUEST_TIME_MS, firstFinish - firstStart, secondFinish - secondStart)
              .isLessThan(UNTHROTTLED_REQUEST_TIME_MS);
        }

    }

    private HttpGet createHttpGet() {
        final HttpGet request = new HttpGet("/");
        request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
        return request;
    }

    private HttpPost createHttpPost() {
        final HttpPost request = new HttpPost("/");
        request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
        final ByteArrayEntity entity = new ByteArrayEntity(largeData);
        entity.setChunked(true);
        request.setEntity(entity);
        return request;
    }
}
