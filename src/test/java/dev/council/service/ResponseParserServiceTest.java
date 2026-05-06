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

import tools.jackson.databind.ObjectMapper;
import dev.council.model.IndividualAgreement.AgreementPoint;
import dev.council.model.IndividualDisagreement.DisagreementPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ResponseParserService demonstrating robustness against LLM format variations.
 */
class ResponseParserServiceTest {

    private ResponseParserService parserService;

    @BeforeEach
    void setUp() {
        parserService = new ResponseParserService(new ObjectMapper());
    }

    @Nested
    @DisplayName("Ranking Parsing")
    class RankingParsingTests {

        @Test
        @DisplayName("parses standard numbered ranking format")
        void parsesStandardFormat() {
            String text = """
                Here is my evaluation of the responses...

                FINAL RANKING:
                1. Response A
                2. Response C
                3. Response B
                """;

            List<String> result = parserService.parseRanking(text);

            assertThat(result).containsExactly("Response A", "Response C", "Response B");
        }

        @Test
        @DisplayName("parses lowercase marker")
        void parsesLowercaseMarker() {
            String text = """
                Here is my evaluation...

                final ranking:
                1. Response B
                2. Response A
                3. Response C
                """;

            List<String> result = parserService.parseRanking(text);

            assertThat(result).containsExactly("Response B", "Response A", "Response C");
        }

        @Test
        @DisplayName("parses mixed case marker")
        void parsesMixedCaseMarker() {
            String text = """
                Final Ranking:
                1. Response C
                2. Response B
                3. Response A
                """;

            List<String> result = parserService.parseRanking(text);

            assertThat(result).containsExactly("Response C", "Response B", "Response A");
        }

        @Test
        @DisplayName("parses bullet point format")
        void parsesBulletFormat() {
            String text = """
                FINAL RANKING:
                - Response A
                - Response B
                - Response C
                """;

            List<String> result = parserService.parseRanking(text);

            assertThat(result).containsExactly("Response A", "Response B", "Response C");
        }

        @Test
        @DisplayName("parses ranking with extra whitespace")
        void parsesWithExtraWhitespace() {
            String text = """
                FINAL   RANKING  :
                  1.   Response A
                  2.   Response B
                  3.   Response C
                """;

            List<String> result = parserService.parseRanking(text);

            assertThat(result).containsExactly("Response A", "Response B", "Response C");
        }

        @Test
        @DisplayName("normalizes response labels to proper format")
        void normalizesResponseLabels() {
            String text = """
                FINAL RANKING:
                1. response a
                2. RESPONSE B
                3. Response   C
                """;

            List<String> result = parserService.parseRanking(text);

            assertThat(result).containsExactly("Response A", "Response B", "Response C");
        }

        @Test
        @DisplayName("parses JSON format ranking")
        void parsesJsonFormat() {
            String json = """
                {
                    "evaluation": "After careful analysis...",
                    "ranking": [
                        {"label": "Response A", "position": 1},
                        {"label": "Response C", "position": 2},
                        {"label": "Response B", "position": 3}
                    ]
                }
                """;

            List<String> result = parserService.parseRanking(json);

            assertThat(result).containsExactly("Response A", "Response C", "Response B");
        }

        @Test
        @DisplayName("returns empty list for null input")
        void returnsEmptyForNull() {
            assertThat(parserService.parseRanking(null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty list for blank input")
        void returnsEmptyForBlank() {
            assertThat(parserService.parseRanking("   ")).isEmpty();
        }

        @Test
        @DisplayName("attempts parsing when marker is missing")
        void attemptsParsingWithoutMarker() {
            String text = """
                1. Response B
                2. Response A
                3. Response C
                """;

            List<String> result = parserService.parseRanking(text);

            assertThat(result).containsExactly("Response B", "Response A", "Response C");
        }
    }

    @Nested
    @DisplayName("Agreement Parsing")
    class AgreementParsingTests {

        @Test
        @DisplayName("parses standard agreement format")
        void parsesStandardFormat() {
            String text = """
                Analysis of agreements...

                Consensus Points:
                1. [Data Quality]: All responses emphasize data quality importance (Response A, Response B, Response C)
                2. [Performance]: Speed is a key consideration (Response A, Response C)
                """;

            List<AgreementPoint> result = parserService.parseAgreementPoints(text);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).topic()).isEqualTo("Data Quality");
            assertThat(result.get(0).description()).isEqualTo("All responses emphasize data quality importance");
            assertThat(result.get(0).agreeingResponses()).containsExactly("Response A", "Response B", "Response C");
        }

        @Test
        @DisplayName("parses lowercase marker")
        void parsesLowercaseMarker() {
            String text = """
                consensus points:
                1. [Testing]: Testing is important (Response A, Response B)
                """;

            List<AgreementPoint> result = parserService.parseAgreementPoints(text);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).topic()).isEqualTo("Testing");
        }

        @Test
        @DisplayName("parses bullet point format")
        void parsesBulletFormat() {
            String text = """
                Consensus Points:
                - [Security]: Security matters (Response A, Response B)
                - [Scalability]: Must scale well (Response B, Response C)
                """;

            List<AgreementPoint> result = parserService.parseAgreementPoints(text);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("parses JSON format agreement")
        void parsesJsonFormat() {
            String json = """
                {
                    "analysis": "The responses show agreement on...",
                    "consensusPoints": [
                        {
                            "topic": "Architecture",
                            "description": "All favor microservices",
                            "agreeingResponses": ["Response A", "Response B"]
                        }
                    ]
                }
                """;

            List<AgreementPoint> result = parserService.parseAgreementPoints(json);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).topic()).isEqualTo("Architecture");
            assertThat(result.get(0).agreeingResponses()).containsExactly("Response A", "Response B");
        }

        @Test
        @DisplayName("returns empty list for null input")
        void returnsEmptyForNull() {
            assertThat(parserService.parseAgreementPoints(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Disagreement Parsing")
    class DisagreementParsingTests {

        @Test
        @DisplayName("parses standard disagreement format")
        void parsesStandardFormat() {
            String text = """
                Analysis of disagreements...

                Divergence Points:
                1. [Database Choice]: Opinions differ on database technology
                    - Response A: Prefers PostgreSQL for reliability
                    - Response B: Recommends MongoDB for flexibility
                    - Response C: Suggests Redis for speed
                """;

            List<DisagreementPoint> result = parserService.parseDisagreementPoints(text);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).topic()).isEqualTo("Database Choice");
            assertThat(result.get(0).positions()).hasSize(3);
            assertThat(result.get(0).positions().get(0).responseLabel()).isEqualTo("Response A");
            assertThat(result.get(0).positions().get(0).stance()).isEqualTo("Prefers PostgreSQL for reliability");
        }

        @Test
        @DisplayName("parses multiple disagreement points")
        void parsesMultiplePoints() {
            String text = """
                Divergence Points:
                1. [Framework]: Different framework preferences
                    - Response A: React
                    - Response B: Vue
                2. [Language]: Language choices vary
                    - Response A: TypeScript
                    - Response C: JavaScript
                """;

            List<DisagreementPoint> result = parserService.parseDisagreementPoints(text);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).topic()).isEqualTo("Framework");
            assertThat(result.get(1).topic()).isEqualTo("Language");
        }

        @Test
        @DisplayName("parses lowercase marker")
        void parsesLowercaseMarker() {
            String text = """
                divergence points:
                1. [Approach]: Different approaches
                    - Response A: Favors simplicity
                    - Response B: Favors completeness
                """;

            List<DisagreementPoint> result = parserService.parseDisagreementPoints(text);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("parses JSON format disagreement")
        void parsesJsonFormat() {
            String json = """
                {
                    "analysis": "The responses diverge on...",
                    "divergencePoints": [
                        {
                            "topic": "Deployment",
                            "description": "Deployment strategies differ",
                            "positions": [
                                {"responseLabel": "Response A", "stance": "Kubernetes"},
                                {"responseLabel": "Response B", "stance": "Docker Compose"}
                            ]
                        }
                    ]
                }
                """;

            List<DisagreementPoint> result = parserService.parseDisagreementPoints(json);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).topic()).isEqualTo("Deployment");
            assertThat(result.get(0).positions()).hasSize(2);
        }

        @Test
        @DisplayName("returns empty list for null input")
        void returnsEmptyForNull() {
            assertThat(parserService.parseDisagreementPoints(null)).isEmpty();
        }
    }
}
