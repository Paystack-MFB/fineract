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
package org.apache.fineract.portfolio.collateral.api;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class CollateralApiConstants {

    private CollateralApiConstants() {

    }

    public static final String COLLATERAL_CODE_NAME = "LoanCollateral";

    /***
     * Enum of all parameters passed in while creating/updating a collateral
     ***/
    public enum CollateralJSONinputParams {

        LOAN_ID("loanId"), //
        COLLATERAL_ID("collateralId"), //
        COLLATERAL_TYPE_ID("collateralTypeId"), //
        VALUE("value"), //
        DESCRIPTION("description"); //

        private final String value;

        CollateralJSONinputParams(final String value) {
            this.value = value;
        }

        public static Set<String> getAllValues() {
            return Arrays.stream(values()).map(CollateralJSONinputParams::getValue).collect(Collectors.toSet());
        }

        @Override
        public String toString() {
            return name().replaceAll("_", " ");
        }

        public String getValue() {
            return this.value;
        }
    }
}
