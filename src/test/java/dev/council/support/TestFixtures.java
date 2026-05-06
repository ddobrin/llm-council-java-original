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
package dev.council.support;

import dev.council.model.CouncilMember;
import dev.council.model.CouncilSession;
import dev.council.model.IndividualResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;

/**
 * Shared test fixtures for council tests. Centralizes the 14-arg CouncilSession
 * constructor and the canned Spring AI ChatResponse construction needed for
 * mocking the streaming responses from ChatClientProvider.
 */
public final class TestFixtures {

    private TestFixtures() {}

    public static CouncilSession sessionFor(String id, String topic) {
        return new CouncilSession(
                id,
                topic,
                Instant.now(),
                CouncilSession.CouncilStage.PENDING,
                List.of(),
                List.of(),
                java.util.Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );
    }

    public static CouncilMember memberOf(String id, String provider, String modelId) {
        return new CouncilMember(id, "Test " + id, provider, modelId, "Test member " + id);
    }

    public static IndividualResponse responseOf(String label, String modelId, String content) {
        return new IndividualResponse(
                modelId,
                "Test " + modelId,
                content,
                null,
                Instant.now(),
                100L,
                42L
        );
    }

    /**
     * Returns a Flux of mocked ChatResponse instances, each containing one of the
     * given chunks as the assistant message text. Used to simulate Spring AI's
     * streaming output from ChatClientProvider.generateResponse(...).
     */
    public static Flux<ChatResponse> mockStreamingResponse(String... chunks) {
        return Flux.fromArray(chunks).map(chunk -> {
            AssistantMessage message = new AssistantMessage(chunk);
            Generation generation = new Generation(message, ChatGenerationMetadata.NULL);
            return new ChatResponse(List.of(generation));
        });
    }
}
