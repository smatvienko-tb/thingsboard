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

import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.msg.TbMsg;

/**
 * Request-scoped context handed to every {@link AgentTool} invocation.
 *
 * @param ctx                  rule engine context
 * @param msg                  incoming message that started the agent run
 * @param originator           originator of the incoming message
 * @param stateEntityId        entity (usually a dedicated asset) that holds agent state and memory attributes
 * @param restrictToOriginator when {@code true}, data-reading tools ignore the {@code entityId} argument
 *                             supplied by the model and always read from the originator
 */
public record AgentToolContext(TbContext ctx,
                               TbMsg msg,
                               EntityId originator,
                               EntityId stateEntityId,
                               boolean restrictToOriginator) {

    public TenantId tenantId() {
        return ctx.getTenantId();
    }

}
