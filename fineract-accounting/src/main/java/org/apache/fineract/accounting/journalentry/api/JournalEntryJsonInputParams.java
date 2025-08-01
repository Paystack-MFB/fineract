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
package org.apache.fineract.accounting.journalentry.api;

import java.util.HashSet;
import java.util.Set;

/***
 * Enum of all parameters passed in while creating/updating a journal Entry
 ***/
public enum JournalEntryJsonInputParams {

    OFFICE_ID("officeId"), //
    TRANSACTION_DATE("transactionDate"), //
    COMMENTS("comments"), //
    CREDITS("credits"), //
    DEBITS("debits"), //
    LOCALE("locale"), //
    DATE_FORMAT("dateFormat"), //
    REFERENCE_NUMBER("referenceNumber"), //
    USE_ACCOUNTING_RULE("useAccountingRule"), //
    ACCOUNTING_RULE("accountingRule"), //
    AMOUNT("amount"), //
    CURRENCY_CODE("currencyCode"), //
    PAYMENT_TYPE_ID("paymentTypeId"), //
    ACCOUNT_NUMBER("accountNumber"), //
    CHECK_NUMBER("checkNumber"), //
    ROUTING_CODE("routingCode"), //
    RECEIPT_NUMBER("receiptNumber"), //
    BANK_NUMBER("bankNumber"), //
    EXTERNAL_ASSET_OWNER("externalAssetOwner"); //

    private final String value;

    JournalEntryJsonInputParams(final String value) {
        this.value = value;
    }

    private static final Set<String> values = new HashSet<>();

    static {
        for (final JournalEntryJsonInputParams type : JournalEntryJsonInputParams.values()) {
            values.add(type.value);
        }
    }

    public static Set<String> getAllValues() {
        return values;
    }

    @Override
    public String toString() {
        return name().replace("_", " ");
    }

    public String getValue() {
        return this.value;
    }
}
