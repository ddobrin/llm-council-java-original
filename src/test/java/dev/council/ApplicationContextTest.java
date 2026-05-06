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
package dev.council;

import dev.council.client.ChatClientRegistry;
import dev.council.model.CouncilMember;
import dev.council.service.CouncilService;
import dev.council.service.storage.ConversationStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-loading smoke test that verifies the Spring application context wires
 * correctly without requiring external API keys or GCP credentials.
 *
 * <h3>Why the "local" profile?</h3>
 * {@code application-local.properties} disables Stackdriver metrics export and
 * clears the GCP JSON log formatter. {@code ObservabilityConfig} is also
 * profile-gated: {@code @Profile("!local")} prevents {@code TraceExporter}
 * from being created — which would otherwise fail without Application Default
 * Credentials.
 *
 * <h3>Why {@code council.warmup.enabled=false}?</h3>
 * {@code AbstractChatClient} resolves its underlying {@code ChatClient} lazily
 * through a {@code Supplier}, so simply constructing the provider beans does not
 * touch any SDK or credentials. {@code ClientWarmupConfig}, however, fires on
 * {@code ApplicationReadyEvent} and calls {@code warmup()} on every provider —
 * which would force the suppliers to resolve, hitting Vertex AI / Anthropic
 * credential lookups. Disabling warmup keeps the context exclusively in-process.
 *
 * <h3>What this catches</h3>
 * <ul>
 *   <li>Binding failures in {@code council.yaml} → {@code CouncilProperties}</li>
 *   <li>Missing/misconfigured models in {@code CouncilConfig} bean factory methods</li>
 *   <li>Broken wiring across the manual bean graph (autoconfiguration is disabled
 *       for Spring AI providers)</li>
 *   <li>{@code ChatClientConfig} bean wiring — every model declared in council.yaml
 *       must have a matching {@code ChatClientProvider} bean</li>
 *   <li>{@code @ImportRuntimeHints} issues (class not found at hint registration)</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "council.warmup.enabled=false"
)
@ActiveProfiles("local")
class ApplicationContextTest {

    @Autowired
    private List<CouncilMember> councilMembers;

    @Autowired
    private CouncilMember chairmanModel;

    @Autowired
    private CouncilMember titleModel;

    @Autowired
    private CouncilService councilService;

    @Autowired
    private ConversationStorage conversationStorage;

    @Autowired
    private ChatClientRegistry chatClientRegistry;

    @Test
    @DisplayName("Spring context loads without errors")
    void contextLoads() {
        // Passes if the context starts without throwing a BeanCreationException.
        // Exercises: council.yaml binding, disabled AI autoconfiguration,
        // CouncilConfig + ChatClientConfig bean wiring, and NativeRuntimeHints registration.
    }

    @Test
    @DisplayName("Council members are populated and structurally valid from council.yaml")
    void councilMembersArePopulatedFromConfig() {
        assertThat(councilMembers).isNotEmpty();
        assertThat(councilMembers).allSatisfy(member -> {
            assertThat(member.id()).as("member id").isNotBlank();
            assertThat(member.name()).as("member name").isNotBlank();
            assertThat(member.provider()).as("member provider").isNotBlank();
            assertThat(member.modelId()).as("member modelId").isNotBlank();
        });
    }

    @Test
    @DisplayName("Chairman model is structurally valid (may resolve from council or available models)")
    void chairmanModelIsWired() {
        assertThat(chairmanModel).isNotNull();
        assertThat(chairmanModel.modelId()).isNotBlank();
        assertThat(chairmanModel.name()).isNotBlank();
        assertThat(chairmanModel.provider()).isNotBlank();
    }

    @Test
    @DisplayName("Title model bean is wired correctly")
    void titleModelIsWired() {
        assertThat(titleModel).isNotNull();
        assertThat(titleModel.modelId()).isNotBlank();
    }

    @Test
    @DisplayName("ChatClientRegistry has a provider registered for every council member's model ID")
    void chatClientRegistryHasProviderForEachCouncilMember() {
        assertThat(chatClientRegistry).isNotNull();
        councilMembers.forEach(member ->
                assertThat(chatClientRegistry.hasClient(member.modelId()))
                        .as("ChatClientRegistry should have a provider for modelId '%s'", member.modelId())
                        .isTrue()
        );
    }
}
