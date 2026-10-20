package com.scraper.leadscraper.controller;

import com.scraper.leadscraper.dto.GoogleSearchLead;
import com.scraper.leadscraper.dto.PlaceResponse.Place;
import com.scraper.leadscraper.service.GoogleSearchScraperService;
import com.scraper.leadscraper.service.ScraperService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LeadController {

    private final ScraperService scraperService;
    private final GoogleSearchScraperService googleSearchScraperService;

    public LeadController(ScraperService scraperService,
                          GoogleSearchScraperService googleSearchScraperService) {
        this.scraperService = scraperService;
        this.googleSearchScraperService = googleSearchScraperService;
    }

    @GetMapping("/api/lead")
    public List<Place> getLeads(@RequestParam(defaultValue = "family restaurants in Peoria IL") String query) {
        return scraperService.getLeadsWithoutWebsite(query);
    }

    /**
     * Searches Google for a category, visits each result website, checks for
     * copyright 2023/2024, and extracts phone numbers from matching sites.
     *
     * Example: GET /api/lead/google-search?query=plumbers+in+Chicago&pages=2
     */
    @GetMapping("/api/lead/google-search")
    public List<GoogleSearchLead> getGoogleSearchLeads(
            @RequestParam(defaultValue = "plumbers in Chicago") String query,
            @RequestParam(defaultValue = "1") int pages) {
        return googleSearchScraperService.searchAndExtractLeads(query, pages);
    }
}
