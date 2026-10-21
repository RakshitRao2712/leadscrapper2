package com.scraper.leadscraper.service;

import com.scraper.leadscraper.dto.GoogleSearchLead;
import io.github.bonigarcia.wdm.WebDriverManager;
import jakarta.annotation.PreDestroy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GoogleSearchScraperService {
    private static final Logger log = LoggerFactory.getLogger(GoogleSearchScraperService.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    // Use the final year of a range, not its start. Require a complete four-digit year.
    private static final Pattern COPYRIGHT = Pattern.compile(
            "(?:©|\\(c\\)|copyright)\\s*(?:©\\s*)?(?:(?:19|20)\\d{2}\\s*[-–—/]\\s*)?((?:19|20)\\d{2})(?:\\s*[-–—/]\\s*(\\d{2}))?(?!\\d)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\p{L}\\d])\\+?\\d[\\d(). \\u00a0-]{5,25}\\d(?![\\p{L}\\d])");
    private static final Pattern JSON_PHONE = Pattern.compile(
            "\"telephone\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Set<String> EXCLUDED = Set.of(
            "google.com", "googleapis.com", "gstatic.com", "youtube.com", "googleusercontent.com",
            "yelp.com", "tripadvisor.com", "opentable.com", "doordash.com", "grubhub.com",
            "ubereats.com", "facebook.com", "instagram.com", "foursquare.com", "eater.com",
            "theinfatuation.com", "wikipedia.org", "timeout.com", "thrillist.com", "michelin.com",
            "resy.com", "yellowpages.com", "menupix.com", "seamless.com");

    private final int maxResults;
    private final Duration timeout;
    private final HttpClient client;
    private final ExecutorService workers;
    // One browser search at a time across requests; website work uses the shared bounded pool.
    private final Semaphore browserSlot = new Semaphore(1);

    public GoogleSearchScraperService(
            @Value("${google.search.max.results:20}") int maxResults,
            @Value("${google.search.page.timeout:10}") int timeoutSeconds,
            @Value("${google.search.concurrency:8}") int concurrency) {
        if (maxResults < 1 || maxResults > 200 || timeoutSeconds < 1 || timeoutSeconds > 60
                || concurrency < 1 || concurrency > 32) {
            throw new IllegalArgumentException("Invalid Google search limits (results 1–200, timeout 1–60, concurrency 1–32)");
        }
        this.maxResults = maxResults;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.client = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        this.workers = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(200), new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
        client.close();
    }

    public List<GoogleSearchLead> searchAndExtractLeads(String query, int pages) {
        if (query == null || query.isBlank() || query.length() > 500 || pages < 1 || pages > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Supply a query of 1–500 characters and pages between 1 and 10");
        }
        long started = System.nanoTime();
        List<GoogleSearchResult> results = scrapeGoogleSearchResults(query.trim(), pages);
        List<Future<GoogleSearchLead>> tasks = new ArrayList<>();
        try {
            for (GoogleSearchResult result : results) {
                tasks.add(workers.submit(() -> processWebsite(result)));
            }
            List<GoogleSearchLead> leads = new ArrayList<>();
            for (Future<GoogleSearchLead> task : tasks) {
                GoogleSearchLead lead = task.get();
                if (lead != null) leads.add(lead);
            }
            log.info("Checked {} websites; found {} leads in {} ms", results.size(), leads.size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return leads;
        } catch (RejectedExecutionException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Scraper busy; retry shortly", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Scrape interrupted", e);
        } catch (ExecutionException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Website processing failed", e);
        } finally {
            tasks.forEach(task -> { if (!task.isDone()) task.cancel(true); });
        }
    }

    protected List<GoogleSearchResult> scrapeGoogleSearchResults(String query, int pages) {
        if (!browserSlot.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google search busy; retry shortly");
        }
        WebDriver driver = null;
        try {
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new", "--disable-gpu", "--disable-dev-shm-usage",
                    "--window-size=1920,1080", "--blink-settings=imagesEnabled=false");
            options.setPageLoadStrategy(org.openqa.selenium.PageLoadStrategy.EAGER);
            driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(timeout);
            // No implicit waits: a missing heading or consent dialog must not cost four seconds.
            driver.manage().timeouts().implicitlyWait(Duration.ZERO);
            Map<String, GoogleSearchResult> results = new LinkedHashMap<>();
            for (int page = 0; page < pages && results.size() < maxResults; page++) {
                String url = "https://www.google.com/search?q=" +
                        URLEncoder.encode(query, StandardCharsets.UTF_8) + "&start=" + page * 10;
                try {
                    driver.get(url);
                } catch (org.openqa.selenium.TimeoutException ignored) {
                    // An eager navigation may time out even though results are already present.
                }
                if (isBlocked(driver.getCurrentUrl(), driver.getPageSource())) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Google blocked the search with a challenge. Retry later.");
                }
                var consent = driver.findElements(By.xpath(
                        "//button[contains(.,'Accept all') or contains(.,'I agree') or @id='L2AGLb']"));
                if (!consent.isEmpty()) consent.getFirst().click();
                new WebDriverWait(driver, timeout).until(d ->
                        isBlocked(d.getCurrentUrl(), d.getPageSource()) || !d.findElements(By.cssSelector("a h3")).isEmpty()
                                || d.getPageSource().contains("did not match any documents"));
                String html = driver.getPageSource();
                if (isBlocked(driver.getCurrentUrl(), html)) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google search challenge; retry later");
                }
                // One DOM snapshot instead of many remote WebDriver calls per link.
                for (GoogleSearchResult result : parseSearchResults(html)) {
                    results.putIfAbsent(siteKey(URI.create(result.url())), result);
                    if (results.size() >= maxResults) break;
                }
                if (html.contains("did not match any documents")) break;
            }
            return new ArrayList<>(results.values());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Google search failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not load Google results. Check Chrome installation or retry later.", e);
        } finally {
            try { if (driver != null) driver.quit(); }
            finally { browserSlot.release(); }
        }
    }

    static boolean isBlocked(String url, String html) {
        String lower = html.toLowerCase(Locale.ROOT);
        return url.contains("/sorry/") || lower.contains("our systems have detected unusual traffic")
                || lower.contains("id=\"captcha-form\"");
    }

    List<GoogleSearchResult> parseSearchResults(String html) {
        Map<String, GoogleSearchResult> results = new LinkedHashMap<>();
        Document doc = Jsoup.parse(html, "https://www.google.com");
        for (Element heading : doc.select("a h3")) {
            Element link = heading.closest("a");
            try {
                URI uri = URI.create(link.absUrl("href"));
                if (uri.getPath().equals("/url") && uri.getRawQuery() != null) {
                    for (String part : uri.getRawQuery().split("&")) {
                        if (part.startsWith("q=") || part.startsWith("url=")) {
                            uri = URI.create(URLDecoder.decode(part.substring(part.indexOf('=') + 1), StandardCharsets.UTF_8));
                            break;
                        }
                    }
                }
                if (!isWebsite(uri) || excluded(uri.getHost())) continue;
                URI clean = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null);
                Element container = link.closest("div.MjjYud, div.g");
                results.putIfAbsent(siteKey(clean), new GoogleSearchResult(clean.toString(), heading.text(),
                        container == null ? "" : container.text()));
                if (results.size() >= maxResults) break;
            } catch (Exception ignored) { /* Ignore malformed search links. */ }
        }
        return new ArrayList<>(results.values());
    }

    GoogleSearchLead processWebsite(GoogleSearchResult result) {
        try {
            Document doc = fetch(URI.create(result.url()));
            if (doc == null) return null;
            String year = findCopyrightYear(doc);
            // Search results can point to a menu or service page with no footer.
            URI landing = URI.create(doc.location());
            if (year == null && landing.getPath() != null && !landing.getPath().matches("/?")) {
                Document home = fetch(landing.resolve("/"));
                if (home != null) { doc = home; year = findCopyrightYear(doc); }
            }
            if (year == null) return null;
            List<String> phones = extractPhoneNumbers(doc);
            if (phones.isEmpty()) {
                Set<String> contacts = new LinkedHashSet<>();
                for (Element link : doc.select("a[href]")) {
                    URI contact;
                    try { contact = URI.create(link.absUrl("href")); }
                    catch (IllegalArgumentException e) { continue; }
                    if (isWebsite(contact) && siteKey(contact).equals(siteKey(URI.create(doc.location())))
                            && (link.text() + " " + contact.getPath()).toLowerCase(Locale.ROOT).contains("contact")) {
                        contacts.add(contact.toString().split("#", 2)[0]);
                    }
                    if (contacts.size() == 2) break;
                }
                for (String contact : contacts) {
                    Document contactDoc = fetch(URI.create(contact));
                    if (contactDoc != null) phones = extractPhoneNumbers(contactDoc);
                    if (!phones.isEmpty()) break;
                }
            }
            if (phones.isEmpty()) return null;
            return new GoogleSearchLead(doc.location(), result.title().isBlank() ? doc.title() : result.title(),
                    phones, year, result.snippet());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("Skipping {}: {}", result.url(), e.getMessage());
            return null;
        }
    }

    private Document fetch(URI uri) throws InterruptedException {
        if (!isWebsite(uri)) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("User-Agent", USER_AGENT).header("Accept", "text/html,application/xhtml+xml").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type")
                    .orElse("text/html").toLowerCase(Locale.ROOT).contains("html")) return null;
            return Jsoup.parse(response.body(), response.uri().toString());
        } catch (java.io.IOException | IllegalArgumentException e) {
            log.debug("Fetch failed for {}: {}", uri, e.getMessage());
            return null;
        }
    }

    static String findCopyrightYear(Document doc) {
        // Ignore scripts, comments and template source. Prefer footer notices over article text.
        String text = doc.select("footer, [role=contentinfo], .footer, #footer, .copyright, #copyright").text();
        if (text.isBlank()) text = doc.body() == null ? "" : doc.body().text();
        Matcher matcher = COPYRIGHT.matcher(text);
        int latest = 0;
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            if (matcher.group(2) != null) {
                int end = Integer.parseInt(matcher.group(2));
                int expanded = year / 100 * 100 + end;
                year = expanded < year ? expanded + 100 : expanded;
            }
            latest = Math.max(latest, year);
        }
        return latest == 2023 || latest == 2024 ? Integer.toString(latest) : null;
    }

    static List<String> extractPhoneNumbers(Document doc) {
        Map<String, String> phones = new LinkedHashMap<>();
        for (Element link : doc.select("a[href]")) {
            String href = link.attr("href");
            if (href.regionMatches(true, 0, "tel:", 0, 4)) {
                addPhone(phones, href.substring(4).split("[;?]", 2)[0]);
            }
            try {
                URI uri = URI.create(link.absUrl("href"));
                String host = uri.getHost();
                if ("wa.me".equalsIgnoreCase(host)) addPhone(phones, "+" + uri.getPath().substring(1));
                if ("api.whatsapp.com".equalsIgnoreCase(host) && uri.getRawQuery() != null) {
                    for (String part : uri.getRawQuery().split("&")) {
                        if (part.startsWith("phone=")) addPhone(phones, "+" + URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) { }
        }
        for (Element data : doc.select("[itemprop=telephone]")) {
            addPhone(phones, data.hasAttr("content") ? data.attr("content") : data.text());
        }
        for (Element script : doc.select("script[type=application/ld+json]")) {
            Matcher matcher = JSON_PHONE.matcher(script.data());
            while (matcher.find()) addPhone(phones, matcher.group(1));
        }
        if (doc.body() != null) {
            Matcher matcher = PHONE.matcher(doc.body().text());
            while (matcher.find()) addPhone(phones, matcher.group());
        }
        return phones.values().stream().limit(5).toList();
    }

    private static void addPhone(Map<String, String> phones, String value) {
        String digits = value.replaceAll("\\D", "");
        // Conservative public phone extraction, not a claim that a number is mobile or active.
        if (digits.length() < 10 || digits.length() > 15 || digits.matches("(\\d)\\1+")) return;
        phones.putIfAbsent(digits, (value.strip().startsWith("+") ? "+" : "") + digits);
    }

    private static boolean isWebsite(URI uri) {
        return uri.getHost() != null && uri.getUserInfo() == null
                && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
    }

    private static boolean excluded(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.matches("(?:.*\\.)?google\\.[a-z.]+") || EXCLUDED.stream()
                .anyMatch(domain -> lower.equals(domain) || lower.endsWith("." + domain));
    }

    private static String siteKey(URI uri) {
        return uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "")
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    protected record GoogleSearchResult(String url, String title, String snippet) { }
}
