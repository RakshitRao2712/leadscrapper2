package com.scraper.leadscraper.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PlaceResponse(List<Place> places) {
    public record Place(
            @JsonProperty("displayName") DisplayName displayName,
            @JsonProperty("websiteUri") String websiteUri,
            @JsonProperty("formattedAddress") String formattedAddress,
            @JsonProperty("nationalPhoneNumber") String nationalPhoneNumber,
            @JsonProperty("businessStatus") String businessStatus,
            @JsonProperty("userRatingCount") Integer userRatingCount) {
        public record DisplayName(String text) {
        }
    }
}
