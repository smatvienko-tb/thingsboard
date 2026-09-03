/**
 * Copyright © 2016-2026 The Thingsboard Authors
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
package org.thingsboard.rule.engine.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only instruments that let the agent pull the data it needs from the platform.
 * <p>
 * Draft scope: everything is read-only on purpose. Write instruments (RPC, alarm creation, telemetry save)
 * are deliberately out of this first cut - they need a separate permission model.
 */
public final class EntityDataTools {

    private EntityDataTools() {}

    private static final int MAX_KEYS = 50;
    private static final int MAX_HISTORY_POINTS = 500;

    // ------------------------------------------------------------------------------------------------ attributes

    public static final class ReadAttributes implements AgentTool {

        @Override
        public AgentToolType type() {
            return AgentToolType.READ_ATTRIBUTES;
        }

        @Override
        public String name() {
            return "read_attributes";
        }

        @Override
        public ToolSpecification specification() {
            return ToolSpecification.builder()
                    .name(name())
                    .description("Reads attributes of an entity. Use it to fetch configuration, thresholds, " +
                            "device metadata or any other slowly changing key-value data.")
                    .parameters(JsonObjectSchema.builder()
                            .addEnumProperty("scope", List.of("SERVER_SCOPE", "SHARED_SCOPE", "CLIENT_SCOPE"),
                                    "Attribute scope. Defaults to SERVER_SCOPE.")
                            .addProperty("keys", JsonArraySchema.builder()
                                    .items(new JsonStringSchema())
                                    .description("Attribute keys to read. Omit to read all attributes in the scope.")
                                    .build())
                            .addStringProperty("entityId", "Optional target entity id (UUID). Defaults to the message originator.")
                            .addStringProperty("entityType", "Entity type of entityId, e.g. DEVICE or ASSET. Required if entityId is set.")
                            .build())
                    .build();
        }

        @Override
        public ListenableFuture<String> execute(AgentToolContext toolCtx, JsonNode args) {
            EntityId target = resolveTarget(toolCtx, args);
            AttributeScope scope = enumArg(args, "scope", AttributeScope.class, AttributeScope.SERVER_SCOPE);
            List<String> keys = stringList(args.get("keys"));
            var attributesService = toolCtx.ctx().getAttributesService();
            var future = keys.isEmpty()
                    ? attributesService.findAll(toolCtx.tenantId(), target, scope)
                    : attributesService.find(toolCtx.tenantId(), target, scope, keys);
            return Futures.transform(future, entries -> {
                ObjectNode result = JacksonUtil.newObjectNode();
                result.put("entityId", target.getId().toString());
                result.put("entityType", target.getEntityType().name());
                result.put("scope", scope.name());
                ObjectNode values = result.putObject("attributes");
                entries.forEach(e -> putKv(values, e));
                return result.toString();
            }, toolCtx.ctx().getDbCallbackExecutor());
        }

    }

    // ------------------------------------------------------------------------------------------ latest telemetry

    public static final class ReadLatestTelemetry implements AgentTool {

        @Override
        public AgentToolType type() {
            return AgentToolType.READ_LATEST_TELEMETRY;
        }

        @Override
        public String name() {
            return "read_latest_telemetry";
        }

        @Override
        public ToolSpecification specification() {
            return ToolSpecification.builder()
                    .name(name())
                    .description("Reads the latest value of the requested telemetry keys. " +
                            "Use it to learn the current state of a device or asset.")
                    .parameters(JsonObjectSchema.builder()
                            .addProperty("keys", JsonArraySchema.builder()
                                    .items(new JsonStringSchema())
                                    .description("Telemetry keys. Omit to read all latest values.")
                                    .build())
                            .addStringProperty("entityId", "Optional target entity id (UUID). Defaults to the message originator.")
                            .addStringProperty("entityType", "Entity type of entityId, e.g. DEVICE or ASSET.")
                            .build())
                    .build();
        }

        @Override
        public ListenableFuture<String> execute(AgentToolContext toolCtx, JsonNode args) {
            EntityId target = resolveTarget(toolCtx, args);
            List<String> keys = stringList(args.get("keys"));
            var tsService = toolCtx.ctx().getTimeseriesService();
            ListenableFuture<List<TsKvEntry>> future = keys.isEmpty()
                    ? tsService.findAllLatest(toolCtx.tenantId(), target)
                    : tsService.findLatest(toolCtx.tenantId(), target, keys.subList(0, Math.min(keys.size(), MAX_KEYS)));
            return Futures.transform(future, entries -> {
                ObjectNode result = JacksonUtil.newObjectNode();
                result.put("entityId", target.getId().toString());
                ObjectNode values = result.putObject("latest");
                for (TsKvEntry entry : entries) {
                    ObjectNode point = values.putObject(entry.getKey());
                    point.put("ts", entry.getTs());
                    putValue(point, "value", entry);
                }
                return result.toString();
            }, toolCtx.ctx().getDbCallbackExecutor());
        }

    }

    // ----------------------------------------------------------------------------------------- telemetry history

    public static final class ReadTelemetryHistory implements AgentTool {

        @Override
        public AgentToolType type() {
            return AgentToolType.READ_TELEMETRY_HISTORY;
        }

        @Override
        public String name() {
            return "read_telemetry_history";
        }

        @Override
        public ToolSpecification specification() {
            return ToolSpecification.builder()
                    .name(name())
                    .description("Reads a time window of telemetry history for a single key, optionally aggregated. " +
                            "Use it to reason about trends instead of a single point in time.")
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("key", "Telemetry key to read.")
                            .addIntegerProperty("startTs", "Window start, epoch millis.")
                            .addIntegerProperty("endTs", "Window end, epoch millis. Defaults to now.")
                            .addIntegerProperty("limit", "Max number of points, up to " + MAX_HISTORY_POINTS + ".")
                            .addEnumProperty("aggregation", List.of("NONE", "MIN", "MAX", "AVG", "SUM", "COUNT"),
                                    "Aggregation function. Defaults to NONE (raw points).")
                            .addIntegerProperty("intervalMs", "Aggregation interval in millis, required for non-NONE aggregation.")
                            .addStringProperty("entityId", "Optional target entity id (UUID). Defaults to the message originator.")
                            .addStringProperty("entityType", "Entity type of entityId, e.g. DEVICE or ASSET.")
                            .required("key")
                            .build())
                    .build();
        }

        @Override
        public ListenableFuture<String> execute(AgentToolContext toolCtx, JsonNode args) {
            EntityId target = resolveTarget(toolCtx, args);
            String key = text(args, "key", null);
            if (key == null || key.isBlank()) {
                return Futures.immediateFuture(error("'key' argument is required"));
            }
            long now = System.currentTimeMillis();
            long endTs = longArg(args, "endTs", now);
            long startTs = longArg(args, "startTs", endTs - 3_600_000L);
            int limit = (int) Math.min(longArg(args, "limit", 100), MAX_HISTORY_POINTS);
            Aggregation aggregation = enumArg(args, "aggregation", Aggregation.class, Aggregation.NONE);
            long interval = longArg(args, "intervalMs", Math.max(1, (endTs - startTs) / Math.max(1, limit)));

            ReadTsKvQuery query = new BaseReadTsKvQuery(key, startTs, endTs,
                    aggregation == Aggregation.NONE ? 0 : interval, limit, aggregation);
            return Futures.transform(
                    toolCtx.ctx().getTimeseriesService().findAll(toolCtx.tenantId(), target, List.of(query)),
                    entries -> {
                        ObjectNode result = JacksonUtil.newObjectNode();
                        result.put("entityId", target.getId().toString());
                        result.put("key", key);
                        result.put("startTs", startTs);
                        result.put("endTs", endTs);
                        result.put("aggregation", aggregation.name());
                        ArrayNode points = result.putArray("points");
                        for (TsKvEntry entry : entries) {
                            ObjectNode point = points.addObject();
                            point.put("ts", entry.getTs());
                            putValue(point, "value", entry);
                        }
                        return result.toString();
                    }, toolCtx.ctx().getDbCallbackExecutor());
        }

    }

    // ---------------------------------------------------------------------------------------- related entities

    public static final class ListRelatedEntities implements AgentTool {

        @Override
        public AgentToolType type() {
            return AgentToolType.LIST_RELATED_ENTITIES;
        }

        @Override
        public String name() {
            return "list_related_entities";
        }

        @Override
        public ToolSpecification specification() {
            return ToolSpecification.builder()
                    .name(name())
                    .description("Lists entities related to the message originator, so the agent can discover " +
                            "which devices belong to an asset before reading their data.")
                    .parameters(JsonObjectSchema.builder()
                            .addEnumProperty("direction", List.of("FROM", "TO"),
                                    "FROM - entities the originator points to; TO - entities pointing to the originator. Defaults to FROM.")
                            .addStringProperty("relationType", "Optional relation type filter, e.g. 'Contains'.")
                            .build())
                    .build();
        }

        @Override
        public ListenableFuture<String> execute(AgentToolContext toolCtx, JsonNode args) {
            var relationService = toolCtx.ctx().getRelationService();
            EntityId originator = toolCtx.originator();
            boolean from = !"TO".equalsIgnoreCase(text(args, "direction", "FROM"));
            String relationType = text(args, "relationType", null);
            var future = from
                    ? (relationType == null
                        ? relationService.findByFromAsync(toolCtx.tenantId(), originator, RelationTypeGroup.COMMON)
                        : relationService.findByFromAndTypeAsync(toolCtx.tenantId(), originator, relationType, RelationTypeGroup.COMMON))
                    : (relationType == null
                        ? relationService.findByToAsync(toolCtx.tenantId(), originator, RelationTypeGroup.COMMON)
                        : relationService.findByToAndTypeAsync(toolCtx.tenantId(), originator, relationType, RelationTypeGroup.COMMON));
            return Futures.transform(future, relations -> {
                ObjectNode result = JacksonUtil.newObjectNode();
                ArrayNode array = result.putArray("entities");
                relations.forEach(relation -> {
                    EntityId other = from ? relation.getTo() : relation.getFrom();
                    ObjectNode node = array.addObject();
                    node.put("entityId", other.getId().toString());
                    node.put("entityType", other.getEntityType().name());
                    node.put("relationType", relation.getType());
                });
                return result.toString();
            }, toolCtx.ctx().getDbCallbackExecutor());
        }

    }

    // ------------------------------------------------------------------------------------------------- helpers

    static EntityId resolveTarget(AgentToolContext toolCtx, JsonNode args) {
        if (toolCtx.restrictToOriginator()) {
            return toolCtx.originator();
        }
        String entityId = text(args, "entityId", null);
        String entityType = text(args, "entityType", null);
        if (entityId == null || entityType == null) {
            return toolCtx.originator();
        }
        try {
            return EntityIdFactory.getByTypeAndUuid(EntityType.valueOf(entityType.toUpperCase()), entityId);
        } catch (Exception e) {
            return toolCtx.originator();
        }
    }

    static String error(String message) {
        return JacksonUtil.newObjectNode().put("error", message).toString();
    }

    static void putKv(ObjectNode node, KvEntry entry) {
        putValue(node, entry.getKey(), entry);
    }

    static void putValue(ObjectNode node, String field, KvEntry entry) {
        switch (entry.getDataType()) {
            case BOOLEAN -> entry.getBooleanValue().ifPresent(v -> node.put(field, v));
            case LONG -> entry.getLongValue().ifPresent(v -> node.put(field, v));
            case DOUBLE -> entry.getDoubleValue().ifPresent(v -> node.put(field, v));
            case JSON -> entry.getJsonValue().ifPresent(v -> node.set(field, JacksonUtil.toJsonNode(v)));
            default -> node.put(field, entry.getValueAsString());
        }
    }

    static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        } else if (node.isTextual() && !node.asText().isBlank()) {
            // some models return a comma separated string instead of an array
            for (String part : node.asText().split(",")) {
                values.add(part.trim());
            }
        }
        return values;
    }

    static String text(JsonNode args, String field, String defaultValue) {
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? defaultValue : node.asText();
    }

    static long longArg(JsonNode args, String field, long defaultValue) {
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? defaultValue : node.asLong(defaultValue);
    }

    static <E extends Enum<E>> E enumArg(JsonNode args, String field, Class<E> type, E defaultValue) {
        String value = text(args, field, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

}
