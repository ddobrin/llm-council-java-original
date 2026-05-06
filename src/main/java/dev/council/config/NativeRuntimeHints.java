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

import dev.council.model.AggregateDisagreement;
import dev.council.model.CouncilSession;
import dev.council.model.IndividualAgreement;
import dev.council.model.IndividualDisagreement;
import dev.council.model.schema.AgreementOutput;
import dev.council.model.schema.DisagreementOutput;
import dev.council.model.schema.RankingOutput;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public class NativeRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources()
                .registerPattern("council.yaml")
                .registerPattern("system/*.st");

        // MdcTraceLoggingEnhancer: instantiated reflectively by the Cloud Logging
        // Logback appender via the <enhancer> element in logback-spring.xml.
        hints.reflection()
                .registerType(MdcTraceLoggingEnhancer.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        // WebSocketBufferConfigListener: passed by class name to Tomcat's
        // Context.addApplicationListener() in WebSocketConfig, which instantiates
        // the listener reflectively at servlet context initialization.
        hints.reflection()
                .registerType(WebSocketConfig.WebSocketBufferConfigListener.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);

        // Caffeine cache implementation class: Caffeine constructs a class name from
        // the configured features (Strong keys, Strong values, Listener, Maximum, Size,
        // Write expiry → "SSLMSW") and loads it via Class.forName() in LocalCacheFactory.
        // GraalVM cannot trace this dynamic class name construction. The DECLARED_FIELDS
        // category is required because Caffeine accesses the static FACTORY field via VarHandle.
        hints.reflection()
                .registerTypeIfPresent(classLoader,
                        "com.github.benmanes.caffeine.cache.SSLMSW",
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.DECLARED_FIELDS);

        // Schema/DTO classes used with ObjectMapper.readValue() in ResponseParserService.
        // Spring AOT does not trace inside method bodies, so direct ObjectMapper usages
        // are invisible to the AOT compiler and must be registered manually.
        hints.reflection()
                .registerType(RankingOutput.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS)
                .registerType(RankingOutput.RankedResponse.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS)
                .registerType(AgreementOutput.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS)
                .registerType(AgreementOutput.ConsensusPoint.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS)
                .registerType(DisagreementOutput.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS)
                .registerType(DisagreementOutput.DivergencePoint.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS)
                .registerType(DisagreementOutput.DivergencePoint.ResponsePosition.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS);

        // Nested model records used by ConversationStorage via ObjectMapper for session persistence.
        // Spring AOT scans outer record classes via Hilla endpoint types, but nested records
        // defined inside those outer records are not automatically followed.
        hints.reflection()
                .registerType(CouncilSession.CouncilStage.class,
                        MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.ACCESS_DECLARED_FIELDS)
                .registerType(IndividualAgreement.AgreementPoint.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS)
                .registerType(IndividualDisagreement.DisagreementPoint.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS)
                .registerType(IndividualDisagreement.DisagreementPoint.Position.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS)
                .registerType(AggregateDisagreement.AggregatePosition.class,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
