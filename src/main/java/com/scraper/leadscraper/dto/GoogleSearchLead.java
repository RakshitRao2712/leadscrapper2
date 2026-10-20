package com.scraper.leadscraper.dto;

import java.util.List;

/**
 * Represents a lead discovered by searching Google for a specific category,
 * visiting the website, and extracting contact information from sites
 * with outdated copyright notices (© 2023 or © 2024).
 */
public record GoogleSearchLead(
        String websiteUrl,
        String businessName,
        List<String> phoneNumbers,
        String copyrightYear,
        String snippet
) {
}
