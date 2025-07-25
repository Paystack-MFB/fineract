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
package org.apache.fineract.accounting.journalentry.serialization;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.accounting.journalentry.api.JournalEntryJsonInputParams;
import org.apache.fineract.accounting.journalentry.command.JournalEntryCommand;
import org.apache.fineract.accounting.journalentry.command.SingleDebitOrCreditEntryCommand;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.serialization.AbstractFromApiJsonDeserializer;
import org.apache.fineract.infrastructure.core.serialization.FromApiJsonDeserializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.springframework.stereotype.Component;

/**
 * Implementation of {@link FromApiJsonDeserializer} for {@link JournalEntryCommand}'s.
 */
@Component
@RequiredArgsConstructor
public final class JournalEntryCommandFromApiJsonDeserializer extends AbstractFromApiJsonDeserializer<JournalEntryCommand> {

    private final FromJsonHelper fromApiJsonHelper;

    @Override
    public JournalEntryCommand commandFromApiJson(final String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        final Set<String> supportedParameters = JournalEntryJsonInputParams.getAllValues();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, supportedParameters);

        final JsonElement element = this.fromApiJsonHelper.parse(json);

        final Long officeId = this.fromApiJsonHelper.extractLongNamed(JournalEntryJsonInputParams.OFFICE_ID.getValue(), element);
        final String currencyCode = this.fromApiJsonHelper.extractStringNamed(JournalEntryJsonInputParams.CURRENCY_CODE.getValue(),
                element);
        final String comments = this.fromApiJsonHelper.extractStringNamed(JournalEntryJsonInputParams.COMMENTS.getValue(), element);
        final LocalDate transactionDate = this.fromApiJsonHelper
                .extractLocalDateNamed(JournalEntryJsonInputParams.TRANSACTION_DATE.getValue(), element);
        final String referenceNumber = this.fromApiJsonHelper.extractStringNamed(JournalEntryJsonInputParams.REFERENCE_NUMBER.getValue(),
                element);
        final Long accountingRuleId = this.fromApiJsonHelper.extractLongNamed(JournalEntryJsonInputParams.ACCOUNTING_RULE.getValue(),
                element);
        final JsonObject topLevelJsonElement = element.getAsJsonObject();
        final Locale locale = this.fromApiJsonHelper.extractLocaleParameter(topLevelJsonElement);
        final String localeStr = locale.getLanguage() + "_" + locale.getCountry() + '_' + locale.getVariant();
        final BigDecimal amount = this.fromApiJsonHelper.extractBigDecimalNamed(JournalEntryJsonInputParams.AMOUNT.getValue(), element,
                locale);
        final Long paymentTypeId = this.fromApiJsonHelper.extractLongNamed(JournalEntryJsonInputParams.PAYMENT_TYPE_ID.getValue(), element);
        final String accountNumber = this.fromApiJsonHelper.extractStringNamed(JournalEntryJsonInputParams.ACCOUNT_NUMBER.getValue(),
                element);
        final String checkNumber = this.fromApiJsonHelper.extractStringNamed(JournalEntryJsonInputParams.CHECK_NUMBER.getValue(), element);
        final String receiptNumber = this.fromApiJsonHelper.extractStringNamed(JournalEntryJsonInputParams.RECEIPT_NUMBER.getValue(),
                element);
        final String bankNumber = this.fromApiJsonHelper.extractStringNamed(JournalEntryJsonInputParams.BANK_NUMBER.getValue(), element);
        final String routingCode = this.fromApiJsonHelper.extractStringNamed(JournalEntryJsonInputParams.ROUTING_CODE.getValue(), element);
        final String externalAssetOwner = this.fromApiJsonHelper
                .extractStringNamed(JournalEntryJsonInputParams.EXTERNAL_ASSET_OWNER.getValue(), element);

        SingleDebitOrCreditEntryCommand[] credits = null;
        SingleDebitOrCreditEntryCommand[] debits = null;
        if (element.isJsonObject()) {
            if (topLevelJsonElement.has(JournalEntryJsonInputParams.CREDITS.getValue())
                    && topLevelJsonElement.get(JournalEntryJsonInputParams.CREDITS.getValue()).isJsonArray()) {
                credits = populateCreditsOrDebitsArray(topLevelJsonElement, locale, JournalEntryJsonInputParams.CREDITS.getValue());
            }
            if (topLevelJsonElement.has(JournalEntryJsonInputParams.DEBITS.getValue())
                    && topLevelJsonElement.get(JournalEntryJsonInputParams.DEBITS.getValue()).isJsonArray()) {
                debits = populateCreditsOrDebitsArray(topLevelJsonElement, locale, JournalEntryJsonInputParams.DEBITS.getValue());
            }
        }
        String dateFormat = this.fromApiJsonHelper.extractStringNamed(JournalEntryJsonInputParams.DATE_FORMAT.getValue(), element);
        return new JournalEntryCommand(officeId, currencyCode, transactionDate, comments, referenceNumber, accountingRuleId, amount,
                paymentTypeId, accountNumber, checkNumber, receiptNumber, bankNumber, routingCode, credits, debits, localeStr, dateFormat,
                externalAssetOwner);
    }

    /**
     * @param topLevelJsonElement
     * @param locale
     * @param paramName
     */
    private SingleDebitOrCreditEntryCommand[] populateCreditsOrDebitsArray(final JsonObject topLevelJsonElement, final Locale locale,
            final String paramName) {
        final JsonArray array = topLevelJsonElement.get(paramName).getAsJsonArray();
        SingleDebitOrCreditEntryCommand[] debitOrCredits = new SingleDebitOrCreditEntryCommand[array.size()];
        for (int i = 0; i < array.size(); i++) {

            final JsonObject creditElement = array.get(i).getAsJsonObject();
            final Set<String> parametersPassedInForCreditsCommand = new HashSet<>();

            final Long glAccountId = this.fromApiJsonHelper.extractLongNamed("glAccountId", creditElement);
            final String comments = this.fromApiJsonHelper.extractStringNamed("comments", creditElement);
            final BigDecimal amount = this.fromApiJsonHelper.extractBigDecimalNamed("amount", creditElement, locale);

            debitOrCredits[i] = new SingleDebitOrCreditEntryCommand(glAccountId, amount, comments, parametersPassedInForCreditsCommand);
        }
        return debitOrCredits;
    }
}
