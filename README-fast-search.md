# Faster Google website leads

Requires Java 21+, Maven, and installed Google Chrome. The Google Maps implementation is unchanged.

```sh
./mvnw spring-boot:run
```

```sh
curl --get 'http://localhost:8080/api/lead/google-search' \
  --data-urlencode 'query=plumbers in Chicago' \
  --data-urlencode 'pages=2'
```

The response keeps the existing fields: `websiteUrl`, `businessName`, `phoneNumbers`, `copyrightYear`, `snippet`.

- Search runs in headless Chrome. Each results page is parsed from one HTML snapshot, avoiding per-link WebDriver calls and implicit waits. No fixed sleep is used.
- Website requests use a reused HTTP client and eight shared workers. Each domain is checked once per search. Website HTML is fetched without Chrome, images, or JavaScript.
- Results are capped by `google.search.max.results` (default 20). `pages` accepts 1–10; Google controls how many results it serves per page.
- Visible copyright notices must end in 2023 or 2024. `© 2018–2024` qualifies; `© 2023–2026` does not. Footer notices take priority over body text; JavaScript template source does not qualify.
- Public phone numbers come from `tel:` links, WhatsApp links, schema telephone fields, and visible text. If needed, up to two linked contact pages are visited on the same site. Phone numbers have 10–15 digits and punctuation is removed. Numbers may be landlines; mobile status and ownership are not verified.
- Search-result subpages can fall back to the homepage for copyright checks. Website fetch failures skip that candidate.
- Google challenges and browser failures return HTTP 502 rather than silently reporting zero leads. Concurrent browser searches or a full worker queue return HTTP 503; invalid inputs return HTTP 400.

Optional settings in `src/main/resources/application.properties`:

```properties
google.search.max.results=20
google.search.page.timeout=10
google.search.concurrency=8
```

Timeout is per page request, not for the whole search. One candidate can require a result-page request, a homepage request, and two contact-page requests. More workers can increase load and blocking; concurrency accepts 1–32. Google may still require consent or present a challenge. The scraper does not solve challenges. Websites requiring JavaScript to display copyright or phones may be missed. A copyright year is a prospecting signal, not proof of when a website was last updated.

Run deterministic extraction, search-parser, concurrency, and local HTTP integration checks:

```sh
./mvnw -Dtest=GoogleSearchScraperServiceTest test
```

These checks do not contact Google. Actual speed and lead count depend on Google responses and target websites; no production throughput claim is implied.
