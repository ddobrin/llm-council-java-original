/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.council.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import dev.council.model.IndividualAgreement.AgreementPoint;
import dev.council.model.IndividualDisagreement.DisagreementPoint;
import dev.council.model.IndividualDisagreement.DisagreementPoint.Position;
import dev.council.model.schema.AgreementOutput;
import dev.council.model.schema.DisagreementOutput;
import dev.council.model.schema.RankingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for parsing LLM text responses into structured data.
 *
 * Provides robust parsing with:
 * - Case-insensitive marker detection
 * - Multiple fallback patterns for common LLM variations
 * - JSON parsing when response starts with '{'
 * - Detailed logging for debugging
 */
@Service
public class ResponseParserService {

    private static final Logger log = LoggerFactory.getLogger(ResponseParserService.class);
    private final ObjectMapper objectMapper;

    // Case-insensitive markers for section detection
    private static final Pattern RANKING_MARKER = Pattern.compile(
            "FINAL\\s+RANKING\\s*:", Pattern.CASE_INSENSITIVE);

    private static final Pattern CONSENSUS_MARKER = Pattern.compile(
            "CONSENSUS\\s+POINTS\\s*:", Pattern.CASE_INSENSITIVE);

    private static final Pattern DIVERGENCE_MARKER = Pattern.compile(
            "DIVERGENCE\\s+POINTS\\s*:", Pattern.CASE_INSENSITIVE);

    // Ranking patterns - numbered and bullet point variations
    private static final Pattern RANKING_NUMBERED = Pattern.compile(
            "^\\s*\\d+\\.?\\s*(Response\\s+[A-Za-z])",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static final Pattern RANKING_BULLETS = Pattern.compile(
            "^\\s*[-*]\\s*(Response\\s+[A-Za-z])",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // Agreement patterns
    private static final Pattern AGREEMENT_STANDARD = Pattern.compile(
            "^\\s*\\d+\\.?\\s*\\[([^\\]]+)]\\s*:\\s*(.+?)\\s*\\(([^)]+)\\)\\s*$",
            Pattern.MULTILINE);

    private static final Pattern AGREEMENT_BULLETS = Pattern.compile(
            "^\\s*[-*]\\s*\\[([^\\]]+)]\\s*:\\s*(.+?)\\s*\\(([^)]+)\\)\\s*$",
            Pattern.MULTILINE);

    private static final Pattern AGREEMENT_NO_BRACKETS = Pattern.compile(
            "^\\s*\\d+\\.?\\s*([^:]+)\\s*:\\s*(.+?)\\s*\\(([^)]+)\\)\\s*$",
            Pattern.MULTILINE);

    // Disagreement patterns
    private static final Pattern TOPIC_PATTERN = Pattern.compile(
            "^\\s*\\d+\\.?\\s*\\[([^\\]]+)]\\s*:\\s*(.+)$",
            Pattern.MULTILINE);

    private static final Pattern TOPIC_BULLETS = Pattern.compile(
            "^\\s*[-*]\\s*\\[([^\\]]+)]\\s*:\\s*(.+)$",
            Pattern.MULTILINE);

    private static final Pattern POSITION_PATTERN = Pattern.compile(
            "^\\s*[-*]\\s*(Response\\s+[A-Za-z])\\s*:\\s*(.+)$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    public ResponseParserService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse ranking from LLM response text.
     * Returns list of response labels in ranked order (e.g., ["Response A", "Response C", "Response B"]).
     */
    public List<String> parseRanking(String text) {
        if (text == null || text.isBlank()) {
            log.warn("Empty text provided for ranking parsing");
            return List.of();
        }

        // Try JSON parsing first if response looks like JSON
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            List<String> jsonResult = parseRankingFromJson(trimmed);
            if (!jsonResult.isEmpty()) {
                log.debug("Successfully parsed ranking from JSON: {} entries", jsonResult.size());
                return jsonResult;
            }
        }

        // Find the ranking section using case-insensitive marker
        Matcher markerMatcher = RANKING_MARKER.matcher(text);
        String rankingSection;
        if (markerMatcher.find()) {
            rankingSection = text.substring(markerMatcher.end());
        } else {
            log.debug("No 'FINAL RANKING:' marker found, attempting to parse entire text");
            rankingSection = text;
        }

        // Try numbered pattern first
        List<String> ranking = extractRankingWithPattern(rankingSection, RANKING_NUMBERED);
        if (!ranking.isEmpty()) {
            log.debug("Parsed {} ranking entries using numbered pattern", ranking.size());
            return ranking;
        }

        // Try bullet point pattern
        ranking = extractRankingWithPattern(rankingSection, RANKING_BULLETS);
        if (!ranking.isEmpty()) {
            log.debug("Parsed {} ranking entries using bullet pattern", ranking.size());
            return ranking;
        }

        log.warn("No ranking entries could be parsed from text");
        return List.of();
    }

    /**
     * Parse agreement points from LLM response text.
     */
    public List<AgreementPoint> parseAgreementPoints(String text) {
        if (text == null || text.isBlank()) {
            log.warn("Empty text provided for agreement parsing");
            return List.of();
        }

        // Try JSON parsing first
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            List<AgreementPoint> jsonResult = parseAgreementFromJson(trimmed);
            if (!jsonResult.isEmpty()) {
                log.debug("Successfully parsed agreement from JSON: {} points", jsonResult.size());
                return jsonResult;
            }
        }

        // Find the consensus section using case-insensitive marker
        Matcher markerMatcher = CONSENSUS_MARKER.matcher(text);
        String consensusSection;
        if (markerMatcher.find()) {
            consensusSection = text.substring(markerMatcher.end());
        } else {
            log.debug("No 'CONSENSUS POINTS:' marker found, attempting to parse entire text");
            consensusSection = text;
        }

        // Try standard pattern first
        List<AgreementPoint> points = extractAgreementWithPattern(consensusSection, AGREEMENT_STANDARD);
        if (!points.isEmpty()) {
            log.debug("Parsed {} agreement points using standard pattern", points.size());
            return points;
        }

        // Try bullet pattern
        points = extractAgreementWithPattern(consensusSection, AGREEMENT_BULLETS);
        if (!points.isEmpty()) {
            log.debug("Parsed {} agreement points using bullet pattern", points.size());
            return points;
        }

        // Try pattern without brackets around topic
        points = extractAgreementWithPattern(consensusSection, AGREEMENT_NO_BRACKETS);
        if (!points.isEmpty()) {
            log.debug("Parsed {} agreement points using no-bracket pattern", points.size());
            return points;
        }

        log.warn("No agreement points could be parsed from text");
        return List.of();
    }

    /**
     * Parse disagreement points from LLM response text.
     */
    public List<DisagreementPoint> parseDisagreementPoints(String text) {
        if (text == null || text.isBlank()) {
            log.warn("Empty text provided for disagreement parsing");
            return List.of();
        }

        // Try JSON parsing first
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            List<DisagreementPoint> jsonResult = parseDisagreementFromJson(trimmed);
            if (!jsonResult.isEmpty()) {
                log.debug("Successfully parsed disagreement from JSON: {} points", jsonResult.size());
                return jsonResult;
            }
        }

        // Find the divergence section using case-insensitive marker
        Matcher markerMatcher = DIVERGENCE_MARKER.matcher(text);
        String divergenceSection;
        if (markerMatcher.find()) {
            divergenceSection = text.substring(markerMatcher.end());
        } else {
            log.debug("No 'DIVERGENCE POINTS:' marker found, attempting to parse entire text");
            divergenceSection = text;
        }

        // Parse using state machine (topics followed by positions)
        List<DisagreementPoint> points = parseDisagreementStateMachine(divergenceSection, TOPIC_PATTERN);
        if (!points.isEmpty()) {
            log.debug("Parsed {} disagreement points using numbered pattern", points.size());
            return points;
        }

        // Try with bullet pattern for topics
        points = parseDisagreementStateMachine(divergenceSection, TOPIC_BULLETS);
        if (!points.isEmpty()) {
            log.debug("Parsed {} disagreement points using bullet pattern", points.size());
            return points;
        }

        log.warn("No disagreement points could be parsed from text");
        return List.of();
    }

    // Helper methods for pattern extraction

    private List<String> extractRankingWithPattern(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        List<String> ranking = new ArrayList<>();
        while (matcher.find()) {
            String label = normalizeResponseLabel(matcher.group(1));
            if (!ranking.contains(label)) {
                ranking.add(label);
            }
        }
        return ranking;
    }

    private List<AgreementPoint> extractAgreementWithPattern(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        List<AgreementPoint> points = new ArrayList<>();
        while (matcher.find()) {
            String topic = matcher.group(1).trim();
            String description = matcher.group(2).trim();
            List<String> responses = parseResponseList(matcher.group(3));
            if (!topic.isEmpty() && !responses.isEmpty()) {
                points.add(new AgreementPoint(topic, description, responses));
            }
        }
        return points;
    }

    private List<DisagreementPoint> parseDisagreementStateMachine(String text, Pattern topicPattern) {
        String[] lines = text.split("\n");
        List<DisagreementPoint> points = new ArrayList<>();
        String currentTopic = null;
        String currentDescription = null;
        List<Position> currentPositions = new ArrayList<>();

        for (String line : lines) {
            Matcher topicMatcher = topicPattern.matcher(line);
            if (topicMatcher.matches()) {
                // Save previous point if exists
                if (currentTopic != null && !currentPositions.isEmpty()) {
                    points.add(new DisagreementPoint(currentTopic, currentDescription, List.copyOf(currentPositions)));
                }
                currentTopic = topicMatcher.group(1).trim();
                currentDescription = topicMatcher.group(2).trim();
                currentPositions = new ArrayList<>();
                continue;
            }

            Matcher posMatcher = POSITION_PATTERN.matcher(line);
            if (posMatcher.matches() && currentTopic != null) {
                String label = normalizeResponseLabel(posMatcher.group(1));
                String stance = posMatcher.group(2).trim();
                currentPositions.add(new Position(label, stance));
            }
        }

        // Don't forget the last point
        if (currentTopic != null && !currentPositions.isEmpty()) {
            points.add(new DisagreementPoint(currentTopic, currentDescription, List.copyOf(currentPositions)));
        }

        return points;
    }

    private List<String> parseResponseList(String text) {
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::normalizeResponseLabel)
                .toList();
    }

    private String normalizeResponseLabel(String label) {
        // Normalize to "Response X" format
        String cleaned = label.trim();
        if (cleaned.toLowerCase().startsWith("response")) {
            // Extract the letter and normalize
            String letter = cleaned.substring("response".length()).trim().toUpperCase();
            if (!letter.isEmpty()) {
                return "Response " + letter.charAt(0);
            }
        }
        return cleaned;
    }

    // JSON parsing methods

    private List<String> parseRankingFromJson(String json) {
        try {
            RankingOutput output = objectMapper.readValue(json, RankingOutput.class);
            if (output.ranking() != null && !output.ranking().isEmpty()) {
                return output.ranking().stream()
                        .sorted((a, b) -> Integer.compare(a.position(), b.position()))
                        .map(r -> normalizeResponseLabel(r.label()))
                        .toList();
            }
        } catch (JacksonException e) {
            log.debug("Failed to parse ranking as JSON: {}", e.getMessage());
        }
        return List.of();
    }

    private List<AgreementPoint> parseAgreementFromJson(String json) {
        try {
            AgreementOutput output = objectMapper.readValue(json, AgreementOutput.class);
            if (output.consensusPoints() != null && !output.consensusPoints().isEmpty()) {
                return output.consensusPoints().stream()
                        .map(cp -> new AgreementPoint(
                                cp.topic(),
                                cp.description(),
                                cp.agreeingResponses().stream()
                                        .map(this::normalizeResponseLabel)
                                        .toList()))
                        .toList();
            }
        } catch (JacksonException e) {
            log.debug("Failed to parse agreement as JSON: {}", e.getMessage());
        }
        return List.of();
    }

    private List<DisagreementPoint> parseDisagreementFromJson(String json) {
        try {
            DisagreementOutput output = objectMapper.readValue(json, DisagreementOutput.class);
            if (output.divergencePoints() != null && !output.divergencePoints().isEmpty()) {
                return output.divergencePoints().stream()
                        .map(dp -> new DisagreementPoint(
                                dp.topic(),
                                dp.description(),
                                dp.positions().stream()
                                        .map(p -> new Position(
                                                normalizeResponseLabel(p.responseLabel()),
                                                p.stance()))
                                        .toList()))
                        .toList();
            }
        } catch (JacksonException e) {
            log.debug("Failed to parse disagreement as JSON: {}", e.getMessage());
        }
        return List.of();
    }
}
