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
package org.apache.fineract.integrationtests.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.util.HashMap;
import org.apache.fineract.client.models.GetLoanRescheduleRequestResponse;
import org.apache.fineract.client.models.PostCreateRescheduleLoansRequest;
import org.apache.fineract.client.models.PostCreateRescheduleLoansResponse;
import org.apache.fineract.client.models.PostUpdateRescheduleLoansRequest;
import org.apache.fineract.client.models.PostUpdateRescheduleLoansResponse;
import org.apache.fineract.client.util.Calls;

public class LoanRescheduleRequestHelper {

    private final RequestSpecification requestSpec;
    private final ResponseSpecification responseSpec;

    private static final String LOAN_RESCHEDULE_REQUEST_URL = "/fineract-provider/api/v1/rescheduleloans";

    public LoanRescheduleRequestHelper(final RequestSpecification requestSpec, final ResponseSpecification responseSpec) {
        this.requestSpec = requestSpec;
        this.responseSpec = responseSpec;
    }

    // TODO: Rewrite to use fineract-client instead!
    // Example: org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper.disburseLoan(java.lang.Long,
    // org.apache.fineract.client.models.PostLoansLoanIdRequest)
    @Deprecated(forRemoval = true)
    public Integer createLoanRescheduleRequest(final String requestJSON) {
        final String URL = LOAN_RESCHEDULE_REQUEST_URL + "?" + Utils.TENANT_IDENTIFIER;
        return Utils.performServerPost(this.requestSpec, this.responseSpec, URL, requestJSON, "resourceId");
    }

    // TODO: Rewrite to use fineract-client instead!
    // Example: org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper.disburseLoan(java.lang.Long,
    // org.apache.fineract.client.models.PostLoansLoanIdRequest)
    @Deprecated(forRemoval = true)
    public HashMap createLoanRescheduleRequestWithFullResponse(final String requestJSON) {
        final String URL = LOAN_RESCHEDULE_REQUEST_URL + "?" + Utils.TENANT_IDENTIFIER;
        return Utils.performServerPost(this.requestSpec, this.responseSpec, URL, requestJSON, "");
    }

    // TODO: Rewrite to use fineract-client instead!
    // Example: org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper.disburseLoan(java.lang.Long,
    // org.apache.fineract.client.models.PostLoansLoanIdRequest)
    @Deprecated(forRemoval = true)
    public Integer rejectLoanRescheduleRequest(final Integer requestId, final String requestJSON) {
        final String URL = LOAN_RESCHEDULE_REQUEST_URL + "/" + requestId + "?" + Utils.TENANT_IDENTIFIER + "&command=reject";

        return Utils.performServerPost(this.requestSpec, this.responseSpec, URL, requestJSON, "resourceId");
    }

    // TODO: Rewrite to use fineract-client instead!
    // Example: org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper.disburseLoan(java.lang.Long,
    // org.apache.fineract.client.models.PostLoansLoanIdRequest)
    @Deprecated(forRemoval = true)
    public Integer approveLoanRescheduleRequest(final Integer requestId, final String requestJSON) {
        final String URL = LOAN_RESCHEDULE_REQUEST_URL + "/" + requestId + "?" + Utils.TENANT_IDENTIFIER + "&command=approve";

        return Utils.performServerPost(this.requestSpec, this.responseSpec, URL, requestJSON, "resourceId");
    }

    // TODO: Rewrite to use fineract-client instead!
    // Example: org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper.disburseLoan(java.lang.Long,
    // org.apache.fineract.client.models.PostLoansLoanIdRequest)
    @Deprecated(forRemoval = true)
    public Object getLoanRescheduleRequest(final Integer requestId, final String param) {
        final String URL = LOAN_RESCHEDULE_REQUEST_URL + "/" + requestId + "?" + Utils.TENANT_IDENTIFIER;

        return Utils.performServerGet(requestSpec, responseSpec, URL, param);
    }

    public GetLoanRescheduleRequestResponse readLoanRescheduleRequest(final Long requestId, final String param) {
        return Calls.ok(FineractClientHelper.getFineractClient().rescheduleLoans.readLoanRescheduleRequest(requestId, param));
    }

    // TODO: Rewrite to use fineract-client instead!
    // Example: org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper.disburseLoan(java.lang.Long,
    // org.apache.fineract.client.models.PostLoansLoanIdRequest)
    @Deprecated(forRemoval = true)
    public void verifyCreationOfLoanRescheduleRequest(final Integer requestId) {
        final String URL = LOAN_RESCHEDULE_REQUEST_URL + "/" + requestId + "?" + Utils.TENANT_IDENTIFIER;

        final Integer id = Utils.performServerGet(requestSpec, responseSpec, URL, "id");
        assertEquals(requestId, id, "ERROR IN CREATING LOAN RESCHEDULE REQUES");
    }

    public PostCreateRescheduleLoansResponse createLoanRescheduleRequest(PostCreateRescheduleLoansRequest request) {
        return Calls.ok(FineractClientHelper.getFineractClient().rescheduleLoans.createLoanRescheduleRequest(request));
    }

    public PostUpdateRescheduleLoansResponse approveLoanRescheduleRequest(Long scheduleId, PostUpdateRescheduleLoansRequest request) {
        return Calls
                .ok(FineractClientHelper.getFineractClient().rescheduleLoans.updateLoanRescheduleRequest(scheduleId, request, "approve"));
    }

    public PostUpdateRescheduleLoansResponse rejectLoanRescheduleRequest(Long scheduleId, PostUpdateRescheduleLoansRequest request) {
        return Calls
                .ok(FineractClientHelper.getFineractClient().rescheduleLoans.updateLoanRescheduleRequest(scheduleId, request, "reject"));
    }
}
