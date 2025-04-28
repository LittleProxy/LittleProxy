package org.littleshoot.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockserver.integration.ClientAndServer;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.http.ClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.remote.http.ClientConfig.defaultConfig;

/**
 * End-to-end test making sure the proxy is able to service simple HTTP requests
 * and stop at the end. Made into a unit test from isopov and nasis's
 * contributions at: <a href="https://github.com/adamfisk/LittleProxy/issues/36">...</a>
 */
public final class EndToEndStoppingTest {
    private static final Logger log = LoggerFactory.getLogger(EndToEndStoppingTest.class);

    private ClientAndServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer = new ClientAndServer(0);
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
    }

    /**
     * This test actually launches a browser!
     */
    @Test
    @Timeout(20)
    public void testWithWebDriver() {
        tryProxyWithBrowser();
    }

    private void tryProxyWithBrowser() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--remote-debugging-pipe");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--proxy-bypass-list=<-loopback>");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-search-engine-choice-screen");
        options.addArguments("--unsafely-disable-devtools-self-xss-warnings");
        options.addArguments(createHeadlessArguments());

        log.info("Starting webdriver with options: {}", options);

        File logFile = new File("target/chromedriver-" + System.currentTimeMillis() + ".txt");
        boolean folderCreated = logFile.getParentFile().mkdirs();
        log.info("Starting webdriver with logs in {} (folder created: {}, folder exists: {})",
          logFile.getAbsolutePath(), folderCreated, logFile.getParentFile().exists());

        ChromeDriverService service = new ChromeDriverService.Builder()
          .withLogFile(logFile)
          .build();
        ClientConfig timeouts = defaultConfig().connectionTimeout(ofSeconds(180)).readTimeout(ofSeconds(70));
        WebDriver driver = new ChromeDriver(service, options, timeouts
        );
        try {
            driver.manage().timeouts().pageLoadTimeout(ofSeconds(40));

            driver.get("https://github.com/littleProxy/LittleProxy//");
            String source = driver.getPageSource();

            // Just make sure it got something within reason
            assertThat(source).hasSizeGreaterThan(100);
        }
        finally {
            driver.quit();
        }
    }

    private List<String> createHeadlessArguments() {
        List<String> arguments = new ArrayList<>();
        arguments.add("--disable-background-networking");
        arguments.add("--enable-features=NetworkService,NetworkServiceInProcess");
        arguments.add("--disable-background-timer-throttling");
        arguments.add("--disable-backgrounding-occluded-windows");
        arguments.add("--disable-breakpad");
        arguments.add("--disable-client-side-phishing-detection");
        arguments.add("--disable-component-extensions-with-background-pages");
        arguments.add("--disable-default-apps");
        arguments.add("--disable-features=TranslateUI");
        arguments.add("--disable-hang-monitor");
        arguments.add("--disable-ipc-flooding-protection");
        arguments.add("--disable-popup-blocking");
        arguments.add("--disable-prompt-on-repost");
        arguments.add("--disable-renderer-backgrounding");
        arguments.add("--disable-sync");
        arguments.add("--force-color-profile=srgb");
        arguments.add("--metrics-recording-only");
        arguments.add("--no-first-run");
        arguments.add("--password-store=basic");
        arguments.add("--use-mock-keychain");
        arguments.add("--hide-scrollbars");
        arguments.add("--mute-audio");
        return arguments;
    }
}
