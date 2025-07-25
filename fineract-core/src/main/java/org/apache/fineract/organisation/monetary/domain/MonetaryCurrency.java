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
package org.apache.fineract.organisation.monetary.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import org.apache.fineract.organisation.monetary.data.CurrencyData;

@Getter
@Embeddable
public class MonetaryCurrency {

    @Column(name = "currency_code", length = 3, nullable = false)
    private String code;

    @Column(name = "currency_digits", nullable = false)
    private int digitsAfterDecimal;

    @Column(name = "currency_multiplesof")
    private Integer inMultiplesOf;

    private transient CurrencyData currencyData;

    protected MonetaryCurrency() {
        this.code = null;
        this.digitsAfterDecimal = 0;
        this.inMultiplesOf = 0;
    }

    public MonetaryCurrency(final String code, final int digitsAfterDecimal, final Integer inMultiplesOf) {
        this.code = code;
        this.digitsAfterDecimal = digitsAfterDecimal;
        this.inMultiplesOf = inMultiplesOf;
    }

    public MonetaryCurrency(final CurrencyData currencyData) {
        this.currencyData = currencyData;
        this.code = currencyData.getCode();
        this.digitsAfterDecimal = currencyData.getDecimalPlaces();
        this.inMultiplesOf = currencyData.getInMultiplesOf();
    }

    public MonetaryCurrency copy() {
        return new MonetaryCurrency(this.code, this.digitsAfterDecimal, this.inMultiplesOf);
    }

    public static MonetaryCurrency fromApplicationCurrency(ApplicationCurrency applicationCurrency) {
        return new MonetaryCurrency(applicationCurrency.getCode(), applicationCurrency.getDecimalPlaces(),
                applicationCurrency.getCurrencyInMultiplesOf());
    }

    public static MonetaryCurrency fromCurrencyData(final CurrencyData currencyData) {
        return new MonetaryCurrency(currencyData);
    }

    public CurrencyData toData() {
        if (currencyData == null) {
            currencyData = new CurrencyData(code, digitsAfterDecimal, inMultiplesOf);
        }
        return currencyData;
    }
}
