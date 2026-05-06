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
package dev.council.config;

import dev.council.config.CouncilProperties.ModelProperties;
import dev.council.model.CouncilMember;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.util.List;

/**
 * Configuration class that creates council member beans from YAML configuration.
 * Uses @EnableConfigurationProperties to bind council.yaml to CouncilProperties.
 */
@Configuration
@EnableConfigurationProperties(CouncilProperties.class)
@ImportRuntimeHints(NativeRuntimeHints.class)
public class CouncilConfig {

    private final CouncilProperties councilProperties;

    public CouncilConfig(CouncilProperties councilProperties) {
        this.councilProperties = councilProperties;
    }

    private static final int MAX_COUNCIL_MEMBERS = 26;

    @Bean
    public List<CouncilMember> councilMembers() {
        int size = councilProperties.getModels().size();
        if (size > MAX_COUNCIL_MEMBERS) {
            throw new IllegalStateException(
                "Council configuration exceeds the maximum supported size of " + MAX_COUNCIL_MEMBERS
                + " members. Configured: " + size
                + ". Models: " + councilProperties.getModels().keySet()
                + ". The label algorithm uses single letters A-Z (max 26 members).");
        }
        return councilProperties.getModels().values().stream()
                .map(this::toCouncilMember)
                .toList();
    }

    @Bean
    public CouncilMember chairmanModel() {
        String chairmanModelId = councilProperties.getChairman().getModel();
        ModelProperties chairmanProps = resolveModel(chairmanModelId);

        if (chairmanProps == null) {
            throw new IllegalStateException("Chairman model not found: " + chairmanModelId
                    + ". Council models: " + councilProperties.getModels().keySet()
                    + ". Available models: " + availableModelKeys());
        }

        return toCouncilMember(chairmanProps);
    }

    @Bean
    public CouncilMember titleModel() {
        String titleModelId = councilProperties.getTitle().getModel();
        ModelProperties titleProps = resolveModel(titleModelId);

        if (titleProps == null) {
            throw new IllegalStateException("Title model not found: " + titleModelId
                    + ". Council models: " + councilProperties.getModels().keySet()
                    + ". Available models: " + availableModelKeys());
        }

        return toCouncilMember(titleProps);
    }

    private ModelProperties resolveModel(String modelKey) {
        if (councilProperties.getAvailable() != null
                && councilProperties.getAvailable().getModels() != null) {
            ModelProperties props = councilProperties.getAvailable().getModels().get(modelKey);
            if (props != null) return props;
        }
        return councilProperties.getModels().get(modelKey);
    }

    private java.util.Set<String> availableModelKeys() {
        if (councilProperties.getAvailable() != null
                && councilProperties.getAvailable().getModels() != null) {
            return councilProperties.getAvailable().getModels().keySet();
        }
        return java.util.Collections.emptySet();
    }

    private CouncilMember toCouncilMember(ModelProperties props) {
        return new CouncilMember(
                props.getId(),
                props.getName(),
                props.getProvider(),
                props.getModelId(),
                props.getAvatarColor()
        );
    }

    /**
     * Protects beans that must remain eagerly initialized when global lazy
     * initialization is enabled (spring.main.lazy-initialization=true).
     *
     * <p>Vaadin/Hilla beans register servlet filters, context listeners, and
     * endpoint initializers during container startup — lazy init would cause
     * them to miss lifecycle events. The WebSocket customizer must run before
     * Tomcat starts to set buffer sizes.</p>
     */
    @Bean
    static LazyInitializationExcludeFilter lazyInitExcludeFilter() {
        return (beanName, beanDefinition, beanType) -> {
            if (beanType == null) {
                return false;
            }
            String typeName = beanType.getName();
            // Vaadin/Hilla must initialize eagerly for servlet lifecycle
            if (typeName.startsWith("com.vaadin.") || typeName.startsWith("dev.hilla.")) {
                return true;
            }
            // WebSocket customizer must run before Tomcat starts
            if (beanType == WebSocketConfig.class) {
                return true;
            }
            return false;
        };
    }
}
