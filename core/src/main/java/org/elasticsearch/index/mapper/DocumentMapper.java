/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import org.elasticsearch.ElasticsearchGenerationException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.IndexAnalyzers;
import org.elasticsearch.index.mapper.MetadataFieldMapper.TypeParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Collections.emptyMap;

/**
 *
 */
public class DocumentMapper implements ToXContent {

    public static class Builder {

        private Map<Class<? extends MetadataFieldMapper>, MetadataFieldMapper> metadataMappers = new LinkedHashMap<>();

        private final RootObjectMapper rootObjectMapper;

        private Map<String, Object> meta = emptyMap();

        private final Mapper.BuilderContext builderContext;

        public Builder(RootObjectMapper.Builder builder, MapperService mapperService) {
            final Settings indexSettings = mapperService.getIndexSettings().getSettings();
            this.builderContext = new Mapper.BuilderContext(indexSettings, new ContentPath(1));
            this.rootObjectMapper = builder.build(builderContext);

            final String type = rootObjectMapper.name();
            DocumentMapper existingMapper = mapperService.documentMapper(type);
            for (Map.Entry<String, MetadataFieldMapper.TypeParser> entry : mapperService.mapperRegistry.getMetadataMapperParsers().entrySet()) {
                final String name = entry.getKey();
                final MetadataFieldMapper existingMetadataMapper = existingMapper == null
                        ? null
                        : (MetadataFieldMapper) existingMapper.mappers().getMapper(name);
                final MetadataFieldMapper metadataMapper;
                if (existingMetadataMapper == null) {
                    final TypeParser parser = entry.getValue();
                    metadataMapper = parser.getDefault(mapperService.fullName(name),
                            mapperService.documentMapperParser().parserContext(builder.name()));
                } else {
                    metadataMapper = existingMetadataMapper;
                }
                metadataMappers.put(metadataMapper.getClass(), metadataMapper);
            }
        }

        public Builder meta(Map<String, Object> meta) {
            this.meta = meta;
            return this;
        }

        public Builder put(MetadataFieldMapper.Builder<?, ?> mapper) {
            MetadataFieldMapper metadataMapper = mapper.build(builderContext);
            metadataMappers.put(metadataMapper.getClass(), metadataMapper);
            return this;
        }

        public DocumentMapper build(MapperService mapperService) {
            Objects.requireNonNull(rootObjectMapper, "Mapper builder must have the root object mapper set");
            Mapping mapping = new Mapping(
                    mapperService.getIndexSettings().getIndexVersionCreated(),
                    rootObjectMapper,
                    metadataMappers.values().toArray(new MetadataFieldMapper[metadataMappers.values().size()]),
                    meta);
            return new DocumentMapper(mapperService, mapping);
        }
    }

    private final MapperService mapperService;

    private final String type;
    private final Text typeText;

    private final CompressedXContent mappingSource;

    private final Mapping mapping;

    private final DocumentParser documentParser;

    private final DocumentFieldMappers fieldMappers;

    private final Map<String, ObjectMapper> objectMappers;

    private final boolean hasNestedObjects;

    public DocumentMapper(MapperService mapperService, Mapping mapping) {
        this.mapperService = mapperService;
        this.type = mapping.root().name();
        this.typeText = new Text(this.type);
        final IndexSettings indexSettings = mapperService.getIndexSettings();
        this.mapping = mapping;
        this.documentParser = new DocumentParser(indexSettings, mapperService.documentMapperParser(), this);

        if (metadataMapper(ParentFieldMapper.class).active()) {
            // mark the routing field mapper as required
            metadataMapper(RoutingFieldMapper.class).markAsRequired();
        }

        // collect all the mappers for this type
        List<ObjectMapper> newObjectMappers = new ArrayList<>();
        List<FieldMapper> newFieldMappers = new ArrayList<>();
        for (MetadataFieldMapper metadataMapper : this.mapping.metadataMappers) {
            if (metadataMapper instanceof FieldMapper) {
                newFieldMappers.add(metadataMapper);
            }
        }
        MapperUtils.collect(this.mapping.root, newObjectMappers, newFieldMappers);

        final IndexAnalyzers indexAnalyzers = mapperService.getIndexAnalyzers();
        this.fieldMappers = new DocumentFieldMappers(newFieldMappers,
                indexAnalyzers.getDefaultIndexAnalyzer(),
                indexAnalyzers.getDefaultSearchAnalyzer(),
                indexAnalyzers.getDefaultSearchQuoteAnalyzer());

        Map<String, ObjectMapper> builder = new HashMap<>();
        for (ObjectMapper objectMapper : newObjectMappers) {
            ObjectMapper previous = builder.put(objectMapper.fullPath(), objectMapper);
            if (previous != null) {
                throw new IllegalStateException("duplicate key " + objectMapper.fullPath() + " encountered");
            }
        }

        boolean hasNestedObjects = false;
        this.objectMappers = Collections.unmodifiableMap(builder);
        for (ObjectMapper objectMapper : newObjectMappers) {
            if (objectMapper.nested().isNested()) {
                hasNestedObjects = true;
            }
        }
        this.hasNestedObjects = hasNestedObjects;

        try {
            mappingSource = new CompressedXContent(this, XContentType.JSON, ToXContent.EMPTY_PARAMS);
        } catch (Exception e) {
            throw new ElasticsearchGenerationException("failed to serialize source for type [" + type + "]", e);
        }
    }

    public Mapping mapping() {
        return mapping;
    }

    public String type() {
        return this.type;
    }

    public CompressedXContent mappingSource() {
        return this.mappingSource;
    }

    public RootObjectMapper root() {
        return mapping.root;
    }

    @SuppressWarnings({"unchecked"})
    public <T extends MetadataFieldMapper> T metadataMapper(Class<T> type) {
        return mapping.metadataMapper(type);
    }

    public TypeFieldMapper typeMapper() {
        return metadataMapper(TypeFieldMapper.class);
    }

    public AllFieldMapper allFieldMapper() {
        return metadataMapper(AllFieldMapper.class);
    }

    public IdFieldMapper idFieldMapper() {
        return metadataMapper(IdFieldMapper.class);
    }

    public RoutingFieldMapper routingFieldMapper() {
        return metadataMapper(RoutingFieldMapper.class);
    }

    public ParentFieldMapper parentFieldMapper() {
        return metadataMapper(ParentFieldMapper.class);
    }

    public TimestampFieldMapper timestampFieldMapper() {
        return metadataMapper(TimestampFieldMapper.class);
    }

    public boolean hasNestedObjects() {
        return hasNestedObjects;
    }

    public DocumentFieldMappers mappers() {
        return this.fieldMappers;
    }

    public Map<String, ObjectMapper> objectMappers() {
        return this.objectMappers;
    }

    // TODO this method looks like it is only used in tests...
    public ParsedDocument parse(String index, String type, String id, BytesReference source) throws MapperParsingException {
        return parse(SourceToParse.source(index, type, id, source, XContentType.JSON));
    }

    public ParsedDocument parse(SourceToParse source) throws MapperParsingException {
        return documentParser.parseDocument(source);
    }

    public boolean isParent(String type) {
        return mapperService.getParentTypes().contains(type);
    }

    public DocumentMapper merge(Mapping mapping, boolean updateAllTypes) {
        Mapping merged = this.mapping.merge(mapping, updateAllTypes);
        return new DocumentMapper(mapperService, merged);
    }

    /**
     * Recursively update sub field types.
     */
    public DocumentMapper updateFieldType(Map<String, MappedFieldType> fullNameToFieldType) {
        Mapping updated = this.mapping.updateFieldType(fullNameToFieldType);
        if (updated == this.mapping) {
            // no change
            return this;
        }
        assert updated == updated.updateFieldType(fullNameToFieldType) : "updateFieldType operation is not idempotent";
        return new DocumentMapper(mapperService, updated);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return mapping.toXContent(builder, params);
    }
}
