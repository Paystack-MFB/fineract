/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.integrationtests.common.externalevents;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fineract.infrastructure.event.external.data.ExternalEventResponse;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BusinessEvent {

    protected String type;
    protected String businessDate;

    public boolean verify(@NotNull ExternalEventResponse externalEvent, DateTimeFormatter formatter) {
        var businessDate = LocalDate.parse(getBusinessDate(), formatter);

        return Objects.equals(externalEvent.getType(), getType()) && Objects.equals(externalEvent.getBusinessDate(), businessDate);
    }
}
