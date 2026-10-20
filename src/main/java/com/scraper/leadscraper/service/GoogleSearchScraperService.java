package com.scraper.leadscraper.service;

import com.scraper.leadscraper.dto.GoogleSearchLead;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service that:
 * 1. Searches Google for a given category/query using Selenium
 * 2. Collects organic result URLs
 * 3. Visits each website and checks for copyright © 2023 or © 2024
 * 4. Extracts phone numbers from matching websites
 */
@Service
public class GoogleSearchScraperService {

    private static final Logger log = LoggerFactory.getLogger(GoogleSearchScraperService.class);

    // Regex patterns for copyright detection — matches "© 2023", "Copyright 2024", etc.
    private static final Pattern COPYRIGHT_PATTERN = Pattern.compile(
            "(?:©|&copy;|\\(c\\)|copyright)\\s*(?:20)?(?:23|24)",
            Pattern.CASE_INSENSITIVE
    );

    // Regex to extract the specific year from the copyright notice
    private static final Pattern COPYRIGHT_YEAR_PATTERN = Pattern.compile(
            "(?:©|&copy;|\\(c\\)|copyright)\\s*(20(?:23|24))",
            Pattern.CASE_INSENSITIVE
    );

    // Common phone number patterns (US, UK, international)
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+?1[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}" +    // US: (555) 123-4567
            "|\\+?\\d{1,4}[-.\\s]?\\(?\\d{1,4}\\)?[-.\\s]?\\d{2,4}[-.\\s]?\\d{2,4}[-.\\s]?\\d{0,4}" + // International
            "|\\d{5}[-.\\s]?\\d{5,6}"  // Generic 10-11 digit numbers
    );

    // Phone href pattern for "tel:" links
    private static final Pattern TEL_HREF_PATTERN = Pattern.compile(
            "tel:([+\\d\\-().\\s]+)"
    );

    @Value("${google.search.max.results:20}")
    private int maxResults;

    @Value("${google.search.page.timeout:10}")
    private int pageTimeoutSeconds;

    /**
     * Searches Google for the given query, collects result URLs, visits each site,
     * checks for copyright 2023/2024, and extracts phone numbers from matching sites.
     *
     * @param query   The search query, e.g. "plumbers in Chicago"
     * @param pages   Number of Google result pages to scrape (default 1)
     * @return List of leads with outdated copyrights and phone numbers
     */
    public List<GoogleSearchLead> searchAndExtractLeads(String query, int pages) {
        log.info("Starting Google search scrape for query: '{}', pages: {}", query, pages);

        // Step 1: Get URLs from Google search results using Selenium
        List<GoogleSearchResult> searchResults = scrapeGoogleSearchResults(query, pages);
        log.info("Found {} search results from Google", searchResults.size());

        // Step 2: Visit each URL and check for copyright + extract phone numbers
        List<GoogleSearchLead> leads = new ArrayList<>();

        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(pageTimeoutSeconds))
                .build();

        for (GoogleSearchResult result : searchResults) {
            try {
                GoogleSearchLead lead = processWebsite(httpClient, result);
                if (lead != null) {
                    leads.add(lead);
                    log.info("✓ Lead found: {} — {} phone(s), copyright {}",
                            lead.websiteUrl(), lead.phoneNumbers().size(), lead.copyrightYear());
                }
            } catch (Exception e) {
                log.warn("Error processing {}: {}", result.url(), e.getMessage());
            }
        }

        log.info("Finished processing. Found {} leads with outdated copyright + phone numbers", leads.size());
        return leads;
    }

    /**
     * Uses Selenium with headless Chrome to scrape Google search results.
     */
    private List<GoogleSearchResult> scrapeGoogleSearchResults(String query, int pages) {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1920,1080");
        options.setPageLoadStrategy(org.openqa.selenium.PageLoadStrategy.EAGER);
        options.addArguments("--blink-settings=imagesEnabled=false");
        options.addArguments("--user-agent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        // Disable automation flags to reduce bot detection
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));

        WebDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(20));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(4));

        Set<GoogleSearchResult> results = new LinkedHashSet<>();

        try {
            for (int page = 0; page < pages; page++) {
                String searchUrl = buildGoogleSearchUrl(query, page * 10);
                log.info("Fetching Google results page {} — URL: {}", page + 1, searchUrl);

                try {
                    driver.get(searchUrl);
                } catch (org.openqa.selenium.TimeoutException e) {
                    log.warn("Page load timed out on {}, proceeding with available DOM", searchUrl);
                }

                // Small delay to let the page settle
                Thread.sleep(1500);

                // Handle cookie consent dialog if present
                try {
                    List<WebElement> consentButtons = driver.findElements(By.xpath(
                            "//button[contains(.,'Accept all') or contains(.,'I agree') or @id='L2AGLb']"));
                    if (!consentButtons.isEmpty()) {
                        consentButtons.get(0).click();
                        Thread.sleep(1000);
                    }
                } catch (Exception ignored) {
                }

                // Extract all external links from Google search results (organic, local pack, knowledge panel)
                List<WebElement> allLinks = driver.findElements(By.cssSelector("a[href]"));
                List<WebElement> candidateElements = new ArrayList<>();
                for (WebElement a : allLinks) {
                    try {
                        String href = a.getAttribute("href");
                        if (href != null && href.startsWith("http") && !isGoogleUrl(href) && !isDirectoryDomain(href)) {
                            candidateElements.add(a);
                        }
                    } catch (Exception ignored) {
                    }
                }
                log.info("Found {} external candidate links on Google page {}", candidateElements.size(), page + 1);

                for (WebElement element : candidateElements) {
                    try {
                        String href = element.getAttribute("href");
                        if (href == null || !href.startsWith("http") || isGoogleUrl(href) || isDirectoryDomain(href)) {
                            continue;
                        }

                        // Try to extract title/heading
                        String title = "";
                        try {
                            WebElement heading = element.findElement(By.xpath(".//h3"));
                            title = heading.getText();
                        } catch (Exception ignored) {
                            title = element.getText();
                        }

                        // Get the snippet from the parent container
                        String snippet = "";
                        try {
                            WebElement parent = element.findElement(By.xpath("./ancestor::div[contains(@class,'g')]"));
                            snippet = parent.getText();
                        } catch (Exception ignored) {
                        }

                        if (!title.isBlank()) {
                            results.add(new GoogleSearchResult(href, title.trim(), snippet.trim()));
                        }
                    } catch (Exception e) {
                        log.debug("Error extracting result element: {}", e.getMessage());
                    }
                }

                if (results.size() >= maxResults) {
                    break;
                }

                // Delay between pages to avoid being blocked
                if (page < pages - 1) {
                    Thread.sleep(3000 + (long) (Math.random() * 2000));
                }
            }
        } catch (Exception e) {
            log.error("Error scraping Google search results", e);
        } finally {
            driver.quit();
        }

        return new ArrayList<>(results);
    }

    /**
     * Visit a website, check for © 2023 or © 2024, and extract phone numbers if found.
     */
    private GoogleSearchLead processWebsite(HttpClient httpClient, GoogleSearchResult result) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(result.url()))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .timeout(Duration.ofSeconds(pageTimeoutSeconds))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.debug("Non-200 response ({}) for {}", response.statusCode(), result.url());
                return null;
            }

            String html = response.body();

            // Check for copyright 2023 or 2024
            String copyrightYear = findCopyrightYear(html);
            if (copyrightYear == null) {
                log.debug("No copyright 2023/2024 found on {}", result.url());
                return null;
            }

            // Extract phone numbers
            List<String> phoneNumbers = extractPhoneNumbers(html);

            if (phoneNumbers.isEmpty()) {
                log.debug("Copyright {} found but no phone numbers on {}", copyrightYear, result.url());
                return null;
            }

            // Extract a clean business name — prefer the title from the search result
            String businessName = result.title();
            if (businessName.isBlank()) {
                Document doc = Jsoup.parse(html);
                businessName = doc.title();
            }

            return new GoogleSearchLead(
                    result.url(),
                    businessName,
                    phoneNumbers,
                    copyrightYear,
                    result.snippet()
            );

        } catch (Exception e) {
            log.debug("Failed to process {}: {}", result.url(), e.getMessage());
            return null;
        }
    }

    /**
     * Checks the HTML for copyright notices containing 2023 or 2024.
     * Returns the year string if found, null otherwise.
     */
    private String findCopyrightYear(String html) {
        // Parse with JSoup to get visible text (more accurate than raw HTML regex)
        Document doc = Jsoup.parse(html);
        String bodyText = doc.body() != null ? doc.body().text() : "";
        String fullText = bodyText + " " + html; // Also search raw HTML for &copy; entities

        Matcher matcher = COPYRIGHT_YEAR_PATTERN.matcher(fullText);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // Fallback: check if copyright pattern matches without extracting year
        Matcher fallback = COPYRIGHT_PATTERN.matcher(fullText);
        if (fallback.find()) {
            String match = fallback.group();
            if (match.contains("23")) return "2023";
            if (match.contains("24")) return "2024";
        }

        return null;
    }

    /**
     * Extracts phone numbers from the HTML using multiple strategies:
     * 1. Look for tel: href links
     * 2. Look for phone patterns in visible text
     * 3. Deduplicate results
     */
    private List<String> extractPhoneNumbers(String html) {
        Set<String> phones = new LinkedHashSet<>();

        Document doc = Jsoup.parse(html);

        // Strategy 1: Extract from tel: links (most reliable)
        doc.select("a[href^=tel:]").forEach(element -> {
            String href = element.attr("href");
            Matcher m = TEL_HREF_PATTERN.matcher(href);
            if (m.find()) {
                String phone = normalizePhone(m.group(1));
                if (isValidPhone(phone)) {
                    phones.add(phone);
                }
            }
        });

        // Strategy 2: Look for phone numbers in common page sections
        // (footer, contact sections, header)
        String[] selectors = {"footer", ".footer", "#footer", ".contact", "#contact",
                ".phone", ".tel", "header", ".header", "#header", "body"};

        for (String selector : selectors) {
            doc.select(selector).forEach(element -> {
                String text = element.text();
                Matcher m = PHONE_PATTERN.matcher(text);
                while (m.find()) {
                    String phone = normalizePhone(m.group());
                    if (isValidPhone(phone)) {
                        phones.add(phone);
                    }
                }
            });

            if (phones.size() >= 3) break; // Don't collect too many
        }

        return new ArrayList<>(phones);
    }

    /**
     * Normalizes a phone number by removing extra whitespace.
     */
    private String normalizePhone(String phone) {
        return phone.replaceAll("\\s+", " ").trim();
    }

    /**
     * Basic validation to filter out false positives.
     */
    private boolean isValidPhone(String phone) {
        // Must have at least 7 digits
        String digitsOnly = phone.replaceAll("[^\\d]", "");
        if (digitsOnly.length() < 7 || digitsOnly.length() > 15) {
            return false;
        }
        // Filter out numbers that look like years or ZIP codes
        if (digitsOnly.length() == 4 && digitsOnly.startsWith("20")) {
            return false;
        }
        return true;
    }

    private String buildGoogleSearchUrl(String query, int start) {
        String encoded = query.replace(" ", "+");
        if (start > 0) {
            return "https://www.google.com/search?q=" + encoded + "&start=" + start;
        }
        return "https://www.google.com/search?q=" + encoded;
    }

    private boolean isGoogleUrl(String url) {
        return url.contains("google.com") || url.contains("google.co")
                || url.contains("googleapis.com") || url.contains("gstatic.com")
                || url.contains("youtube.com") || url.contains("webcache.googleusercontent.com");
    }

    private static final Set<String> DIRECTORY_DOMAINS = Set.of(
            "yelp.com", "tripadvisor.com", "opentable.com", "doordash.com",
            "grubhub.com", "ubereats.com", "facebook.com", "instagram.com",
            "foursquare.com", "eater.com", "theinfatuation.com", "wikipedia.org",
            "timeout.com", "thrillist.com", "michelin.com", "resy.com",
            "yellowpages.com", "menupix.com", "seamless.com"
    );

    private boolean isDirectoryDomain(String url) {
        String lower = url.toLowerCase();
        for (String domain : DIRECTORY_DOMAINS) {
            if (lower.contains(domain)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Internal record to hold raw Google search results before processing.
     */
    private record GoogleSearchResult(String url, String title, String snippet) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GoogleSearchResult that)) return false;
            return url.equals(that.url);
        }

        @Override
        public int hashCode() {
            return url.hashCode();
        }
    }
}
