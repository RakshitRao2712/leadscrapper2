package com.scraper.leadscraper.service;

import tools.jackson.databind.ObjectMapper;
import com.scraper.leadscraper.dto.PlaceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

@Service
public class ScraperService {

    private static final Logger log = LoggerFactory.getLogger(ScraperService.class);

    @Value("${google.places.api.key}")
    private String apiKey;

    @Value("${max.reviews.for.new.business:5}")
    private int maxReviewsForNewBusiness;

    private final ObjectMapper objectMapper;

    public ScraperService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<PlaceResponse.Place> getLeadsWithoutWebsite(String query) {
        String jsonPayload = String.format("{\"textQuery\": \"%s\"}", query);

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://places.googleapis.com/v1/places:searchText"))
                    .header("Content-Type", "application/json")
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask",
                            "places.displayName,places.websiteUri,places.formattedAddress,places.nationalPhoneNumber,places.businessStatus,places.userRatingCount")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Google Places API response status: {}", response.statusCode());
            log.info("Google Places API response body: {}", response.body());

            PlaceResponse placeResponse = objectMapper.readValue(response.body(), PlaceResponse.class);

            if (placeResponse.places() == null)
                return List.of();

            // Stream and filter for missing websites, operational status, and recently added (proxy: low review count)
            return placeResponse.places().stream()
                    .filter(place -> place.websiteUri() == null || place.websiteUri().isEmpty())
                    .filter(place -> "OPERATIONAL".equals(place.businessStatus()))
                    .filter(place -> place.userRatingCount() == null || place.userRatingCount() <= maxReviewsForNewBusiness)
                    .toList();

        } catch (Exception e) {
            log.error("Error fetching leads from Google Places API", e);
            throw new RuntimeException("Error fetching leads from Google Places API", e);
        }
    }
}
