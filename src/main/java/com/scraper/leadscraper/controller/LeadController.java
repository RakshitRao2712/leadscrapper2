package com.scraper.leadscraper.controller;

import com.scraper.leadscraper.dto.PlaceResponse.Place;
import com.scraper.leadscraper.service.ScraperService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LeadController {

    private final ScraperService scraperService;

    public LeadController(ScraperService scraperService) {
        this.scraperService = scraperService;
    }

    @GetMapping("/api/leadsssss")
    public List<Place> getLeads(@RequestParam(defaultValue = "family restaurants in Peoria IL") String query) {
        return scraperService.getLeadsWithoutWebsite(query);
    }
}
