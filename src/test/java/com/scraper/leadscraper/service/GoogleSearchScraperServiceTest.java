package com.scraper.leadscraper.service;

import com.scraper.leadscraper.dto.GoogleSearchLead;
import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class GoogleSearchScraperServiceTest {
    @Test
    void copyrightUsesLatestVisibleYearAndIgnoresTemplateSource() {
        assertEquals("2024", year("<footer>Copyright &copy; 2018–2024 Acme</footer>"));
        assertEquals("2023", year("<footer>© <span>2023</span> Acme</footer>"));
        assertNull(year("<footer>© 2023–2026 Acme</footer>"));
        assertNull(year("<footer>© 2023–26 Acme</footer>"));
        assertEquals("2024", year("<footer>© 2020–24 Acme</footer>"));
        assertNull(year("<footer>© 20230 Acme</footer>"));
        assertNull(year("<script>const template = '© 2024';</script><footer>© 2026</footer>"));
        assertNull(year("<footer>© 2023 Acme. Copyright 2026 Parent</footer>"));
    }

    private String year(String html) {
        return GoogleSearchScraperService.findCopyrightYear(Jsoup.parse(html));
    }

    @Test
    void extractsPublicPhonesFromLinksSchemaAndWhatsappAndDeduplicates() {
        var doc = Jsoup.parse("""
                <a href="tel:+1(312)555-0123;ext=5">Call</a>
                <a href="TEL:+1-312-555-0123">Call again</a>
                <a href="https://wa.me/919876543210">WhatsApp</a>
                <script type="application/ld+json">{"telephone":"+44 20 7946 0958"}</script>
                <p>Call (415) 555-0199</p><p>© 2024. ZIP 90210</p>
                """);
        assertEquals(List.of("+13125550123", "+919876543210", "+442079460958", "4155550199"),
                GoogleSearchScraperService.extractPhoneNumbers(doc));
    }

    @Test
    void parsesOrganicResultsDecodesRedirectsDeduplicatesSitesAndEnforcesLimit() {
        try (var service = new TestService(2, 1)) {
            var results = service.parseSearchResults("""
                    <a href="https://www.example.org/menu"><h3>One</h3></a>
                    <a href="https://example.org/contact"><h3>Same business</h3></a>
                    <a href="https://www.yelp.com/biz/one"><h3>Directory</h3></a>
                    <a href="/url?q=https%3A%2F%2Fother.org%2F%3Fx%3D1&amp;sa=U"><h3>Two</h3></a>
                    <a href="https://third.org"><h3>Three</h3></a>
                    """);
            assertEquals(2, results.size());
            assertEquals("https://other.org/?x=1", results.get(1).url());
        }
    }

    @Test
    void visitsContactPageOnlyAfterCopyrightQualifiesAndUsesRedirectBase() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().set("Location", "/business/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/business/", exchange -> {
            String html = exchange.getRequestURI().getPath().endsWith("contact")
                    ? "<a href='tel:+919876543210'>Call</a>"
                    : "<footer>© 2024</footer><a href='contact'>Contact</a>";
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try (var service = new TestService(20, 2)) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GoogleSearchLead lead = service.processWebsite(
                    new GoogleSearchScraperService.GoogleSearchResult(base + "/start", "Business", ""));
            assertNotNull(lead);
            assertEquals(base + "/business/", lead.websiteUrl());
            assertEquals("2024", lead.copyrightYear());
            assertEquals(List.of("+919876543210"), lead.phoneNumbers());
        } finally { server.stop(0); }
    }

    @Test
    void processesWebsitesConcurrentlyWithoutExceedingWorkerLimit() throws Exception {
        CountDownLatch twoStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (var service = new TestService(20, 2) {
            @Override protected List<GoogleSearchResult> scrapeGoogleSearchResults(String query, int pages) {
                return List.of(new GoogleSearchResult("https://one.org", "One", ""),
                        new GoogleSearchResult("https://two.org", "Two", ""));
            }
            @Override GoogleSearchLead processWebsite(GoogleSearchResult result) {
                twoStarted.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return new GoogleSearchLead(result.url(), result.title(), List.of("+919876543210"), "2024", "");
            }
        }; var caller = Executors.newSingleThreadExecutor()) {
            Future<List<GoogleSearchLead>> result = caller.submit(() -> service.searchAndExtractLeads("plumbers", 1));
            try { assertTrue(twoStarted.await(3, TimeUnit.SECONDS), "Both visits should start before either finishes"); }
            finally { release.countDown(); }
            assertEquals(2, result.get(5, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void rejectsInvalidInputsAndDetectsGoogleChallenges() {
        try (var service = new TestService(20, 2)) {
            assertThrows(ResponseStatusException.class, () -> service.searchAndExtractLeads(" ", 1));
            assertThrows(ResponseStatusException.class, () -> service.searchAndExtractLeads("plumbers", 100));
            assertTrue(GoogleSearchScraperService.isBlocked("https://www.google.com/sorry/index", ""));
        }
    }

    private static class TestService extends GoogleSearchScraperService implements AutoCloseable {
        TestService(int maxResults, int concurrency) { super(maxResults, 2, concurrency); }
    }
}
