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
package dev.council.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndividualResponseTest {

    private IndividualResponse withContent(String content) {
        return new IndividualResponse(
                "test-model", "Test Model", content, null,
                Instant.now(), 100L, 50L);
    }

    @Test
    void isError_returnsTrue_whenContentNull() {
        assertTrue(withContent(null).isError());
    }

    @Test
    void isError_returnsTrue_whenContentEmpty() {
        assertTrue(withContent("").isError());
    }

    @Test
    void isError_returnsTrue_whenContentBlank() {
        assertTrue(withContent("   \n\t  ").isError());
    }

    @Test
    void isError_returnsTrue_whenContentStartsWithErrorPrefix() {
        assertTrue(withContent("[Error: rate limit exceeded]").isError());
    }

    @Test
    void isError_returnsFalse_whenContentValid() {
        assertFalse(withContent("This is a real response.").isError());
    }

    @Test
    void isError_returnsFalse_whenContentMentionsErrorButNotAsPrefix() {
        assertFalse(withContent("The system reported [Error: x] earlier.").isError());
    }
}
