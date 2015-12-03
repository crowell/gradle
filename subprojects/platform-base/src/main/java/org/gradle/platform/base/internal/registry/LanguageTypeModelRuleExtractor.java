/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.platform.base.internal.registry;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.internal.Cast;
import org.gradle.language.base.LanguageSourceSet;
import org.gradle.language.base.internal.registry.LanguageRegistry;
import org.gradle.language.base.internal.registry.NamedLanguageRegistration;
import org.gradle.language.base.plugins.ComponentModelBasePlugin;
import org.gradle.language.base.sources.BaseLanguageSourceSet;
import org.gradle.model.internal.core.*;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;
import org.gradle.model.internal.inspect.MethodRuleDefinition;
import org.gradle.model.internal.manage.schema.ModelSchema;
import org.gradle.model.internal.manage.schema.ModelSchemaStore;
import org.gradle.model.internal.type.ModelType;
import org.gradle.platform.base.InvalidModelException;
import org.gradle.platform.base.LanguageType;
import org.gradle.platform.base.LanguageTypeBuilder;
import org.gradle.platform.base.internal.builder.LanguageTypeBuilderInternal;
import org.gradle.platform.base.internal.builder.TypeBuilderFactory;
import org.gradle.platform.base.internal.builder.TypeBuilderInternal;

import java.util.Set;

public class LanguageTypeModelRuleExtractor extends TypeModelRuleExtractor<LanguageType, LanguageSourceSet, BaseLanguageSourceSet> {

    public LanguageTypeModelRuleExtractor(ModelSchemaStore schemaStore) {
        super("language", LanguageSourceSet.class, BaseLanguageSourceSet.class, LanguageTypeBuilder.class, schemaStore, new TypeBuilderFactory<LanguageSourceSet>() {
            @Override
            public TypeBuilderInternal<LanguageSourceSet> create(ModelSchema<? extends LanguageSourceSet> schema) {
                return new DefaultLanguageTypeBuilder(schema);
            }
        });
    }

    @Override
    protected <R, S> ExtractedModelRule createRegistration(MethodRuleDefinition<R, S> ruleDefinition, ModelType<? extends LanguageSourceSet> type, TypeBuilderInternal<LanguageSourceSet> builder) {
        ImmutableList<Class<?>> dependencies = ImmutableList.<Class<?>>of(ComponentModelBasePlugin.class);
        ModelType<? extends BaseLanguageSourceSet> implementation = determineImplementationType(type, builder);
        String languageName = ((LanguageTypeBuilderInternal) builder).getLanguageName();
        if(StringUtils.isEmpty(languageName)) {
            throw new InvalidModelException(String.format("Language type '%s' cannot be registered without a language name.", type));
        }
        ModelAction mutator = createRegistrationAction(languageName, type, builder.getInternalViews(), implementation, ruleDefinition.getDescriptor());
        return new ExtractedModelAction(ModelActionRole.Defaults, dependencies, mutator);
    }

    private <S extends LanguageSourceSet> ModelAction createRegistrationAction(final String languageName, final ModelType<S> type,
                                                                               final Set<Class<?>> internalViews, final ModelType<? extends BaseLanguageSourceSet> implementation,
                                                                               final ModelRuleDescriptor descriptor) {
        return NoInputsModelAction.of(ModelReference.of(LanguageRegistry.class), descriptor, new Action<LanguageRegistry>() {
            @Override
            public void execute(LanguageRegistry languageRegistry) {
                ModelType<? extends S> castImplementation = Cast.uncheckedCast(implementation);
                languageRegistry.add(new NamedLanguageRegistration<S>(languageName, type, internalViews, castImplementation, descriptor));
            }
        });
    }

    public static class DefaultLanguageTypeBuilder extends AbstractTypeBuilder<LanguageSourceSet> implements LanguageTypeBuilderInternal<LanguageSourceSet> {
        private String languageName;

        public DefaultLanguageTypeBuilder(ModelSchema<? extends LanguageSourceSet> schema) {
            super(LanguageType.class, schema);
        }

        @Override
        public void setLanguageName(String languageName) {
            this.languageName = languageName;
        }

        @Override
        public String getLanguageName() {
            return languageName;
        }
    }
}
