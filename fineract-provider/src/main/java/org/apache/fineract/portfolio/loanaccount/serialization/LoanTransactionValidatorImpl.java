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
package org.apache.fineract.portfolio.loanaccount.serialization;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.dataqueries.data.EntityTables;
import org.apache.fineract.infrastructure.dataqueries.data.StatusEnum;
import org.apache.fineract.infrastructure.dataqueries.service.EntityDatatableChecksWritePlatformService;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.holiday.service.HolidayUtil;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrency;
import org.apache.fineract.organisation.monetary.domain.ApplicationCurrencyRepository;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.exception.CurrencyNotFoundException;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.organisation.workingdays.service.WorkingDaysUtil;
import org.apache.fineract.portfolio.calendar.domain.Calendar;
import org.apache.fineract.portfolio.calendar.domain.CalendarEntityType;
import org.apache.fineract.portfolio.calendar.domain.CalendarInstance;
import org.apache.fineract.portfolio.calendar.domain.CalendarInstanceRepository;
import org.apache.fineract.portfolio.calendar.exception.NotValidRecurringDateException;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.exception.ClientNotActiveException;
import org.apache.fineract.portfolio.collateralmanagement.exception.LoanCollateralAmountNotSufficientException;
import org.apache.fineract.portfolio.common.service.Validator;
import org.apache.fineract.portfolio.group.domain.Group;
import org.apache.fineract.portfolio.group.exception.GroupNotActiveException;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCollateralManagement;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanEvent;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.exception.DateMismatchException;
import org.apache.fineract.portfolio.loanaccount.exception.InvalidLoanStateTransitionException;
import org.apache.fineract.portfolio.loanaccount.exception.InvalidLoanTransactionTypeException;
import org.apache.fineract.portfolio.loanaccount.exception.InvalidRefundDateException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanApplicationDateException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanChargeRefundException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanDisbursalException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanNotFoundException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanRepaymentScheduleNotFoundException;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanaccount.service.LoanUtilService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

@Component("loanTransactionValidator")
@AllArgsConstructor
public final class LoanTransactionValidatorImpl implements LoanTransactionValidator {

    private final FromJsonHelper fromApiJsonHelper;
    private final LoanApplicationValidator fromApiJsonDeserializer;
    private final LoanRepository loanRepository;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final ApplicationCurrencyRepository applicationCurrencyRepository;
    private final LoanUtilService loanUtilService;
    private final EntityDatatableChecksWritePlatformService entityDatatableChecksWritePlatformService;
    private final CalendarInstanceRepository calendarInstanceRepository;
    private final LoanDownPaymentTransactionValidator loanDownPaymentTransactionValidator;
    private final LoanDisbursementValidator loanDisbursementValidator;

    private void throwExceptionIfValidationWarningsExist(final List<ApiParameterError> dataValidationErrors) {
        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.",
                    dataValidationErrors);
        }
    }

    @Override
    public void validateDisbursement(JsonCommand command, boolean isAccountTransfer, Long loanId) {
        String json = command.json();
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, getDisbursementParameters(isAccountTransfer));

        Validator.validateOrThrow("loan.disbursement", baseDataValidator -> {
            final JsonElement element = this.fromApiJsonHelper.parse(json);
            final LocalDate actualDisbursementDate = this.fromApiJsonHelper.extractLocalDateNamed("actualDisbursementDate", element);
            baseDataValidator.reset().parameter("actualDisbursementDate").value(actualDisbursementDate).notNull();

            final String note = this.fromApiJsonHelper.extractStringNamed("note", element);
            baseDataValidator.reset().parameter("note").value(note).notExceedingLengthOf(1000);

            final BigDecimal principal = this.fromApiJsonHelper
                    .extractBigDecimalWithLocaleNamed(LoanApiConstants.principalDisbursedParameterName, element);
            baseDataValidator.reset().parameter(LoanApiConstants.principalDisbursedParameterName).value(principal).ignoreIfNull()
                    .positiveAmount();

            final BigDecimal netDisbursalAmount = this.fromApiJsonHelper
                    .extractBigDecimalWithLocaleNamed(LoanApiConstants.disbursementNetDisbursalAmountParameterName, element);
            baseDataValidator.reset().parameter(LoanApiConstants.disbursementNetDisbursalAmountParameterName).value(netDisbursalAmount)
                    .ignoreIfNull().positiveAmount();

            final BigDecimal emiAmount = this.fromApiJsonHelper
                    .extractBigDecimalWithLocaleNamed(LoanApiConstants.fixedEmiAmountParameterName, element);
            baseDataValidator.reset().parameter(LoanApiConstants.fixedEmiAmountParameterName).value(emiAmount).ignoreIfNull()
                    .positiveAmount().notGreaterThanMax(principal);

            validatePaymentDetails(baseDataValidator, element);

            if (command.parameterExists("postDatedChecks")) {
                this.validateDisbursementWithPostDatedChecks(command.json(), loanId);
            }

            final Loan loan = this.loanRepositoryWrapper.findOneWithNotFoundDetection(loanId, true);
            validateLoanClientIsActive(loan);
            validateLoanGroupIsActive(loan);

            final BigDecimal disbursedAmount = loan.getDisbursedAmount();
            loanDisbursementValidator.compareDisbursedToApprovedOrProposedPrincipal(loan, principal, disbursedAmount);

            if (loan.isChargedOff()) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.disbursal.not.allowed.on.charged.off",
                        "Loan: " + loan.getId() + " disbursement is not allowed on charged-off loan.");
            }

            boolean isSingleDisburseLoan = !loan.getLoanProduct().isMultiDisburseLoan();
            boolean isSingleDisburseNotApprovedOrDisbursedAlready = isSingleDisburseLoan && !(loan.isApproved() && loan.isNotDisbursed());
            boolean isMultiDisburseLoanAndAllTranchesDisbursed = loan.getLoanProduct().isMultiDisburseLoan()
                    && !loan.isAllTranchesNotDisbursed();
            if (isSingleDisburseNotApprovedOrDisbursedAlready || isMultiDisburseLoanAndAllTranchesDisbursed) {
                final String defaultUserMessage = "Loan Disbursal is not allowed. Loan Account is not in approved and not disbursed state.";
                final ApiParameterError error = ApiParameterError
                        .generalError("error.msg.loan.disbursal.account.is.not.approve.not.disbursed.state", defaultUserMessage);
                baseDataValidator.getDataValidationErrors().add(error);
            }

            final Set<LoanCollateralManagement> loanCollateralManagements = loan.getLoanCollateralManagements();

            if ((loanCollateralManagements != null && !loanCollateralManagements.isEmpty()) && loan.getLoanType().isIndividualAccount()) {
                BigDecimal totalCollateral = collectTotalCollateral(loanCollateralManagements);

                // Validate the loan collateral value against the disbursedAmount
                if (disbursedAmount.compareTo(totalCollateral) > 0) {
                    throw new LoanCollateralAmountNotSufficientException(disbursedAmount);
                }
            }

            // validate ActualDisbursement Date Against Expected Disbursement Date
            LoanProduct loanProduct = loan.loanProduct();
            if (loanProduct.isSyncExpectedWithDisbursementDate()) {
                if (!loan.getExpectedDisbursedOnLocalDate().equals(actualDisbursementDate)) {
                    throw new DateMismatchException(actualDisbursementDate, loan.getExpectedDisbursedOnLocalDate());
                }
            }

            entityDatatableChecksWritePlatformService.runTheCheckForProduct(loan.getId(), EntityTables.LOAN.getName(),
                    StatusEnum.DISBURSE.getValue(), EntityTables.LOAN.getForeignKeyColumnNameOnDatatable(), loan.productId());

            ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, null);
            final CalendarInstance calendarInstance = this.calendarInstanceRepository.findCalendarInstanceByEntityId(loan.getId(),
                    CalendarEntityType.LOANS.getValue());
            if (loan.isSyncDisbursementWithMeeting()) {
                validateDisbursementDateWithMeetingDate(actualDisbursementDate, calendarInstance,
                        scheduleGeneratorDTO.isSkipRepaymentOnFirstDayofMonth(), scheduleGeneratorDTO.getNumberOfdays());
            }

            // validate if disbursement date is a holiday or a non-working day
            HolidayDetailDTO holidayDetailDTO = scheduleGeneratorDTO.getHolidayDetailDTO();
            if (!holidayDetailDTO.isAllowTransactionsOnNonWorkingDay()
                    && !WorkingDaysUtil.isWorkingDay(holidayDetailDTO.getWorkingDays(), loan.getDisbursementDate())) {
                final String errorMessage = "Expected disbursement date cannot be on a non working day";
                throw new LoanApplicationDateException("disbursement.date.on.non.working.day", errorMessage,
                        loan.getExpectedDisbursedOnLocalDate());
            }
            if (!holidayDetailDTO.isAllowTransactionsOnHoliday()
                    && HolidayUtil.isHoliday(loan.getDisbursementDate(), holidayDetailDTO.getHolidays())) {
                final String errorMessage = "Expected disbursement date cannot be on a holiday";
                throw new LoanApplicationDateException("disbursement.date.on.holiday", errorMessage,
                        loan.getExpectedDisbursedOnLocalDate());
            }

            if ((loan.getStatus().isActive() || loan.getStatus().isClosedObligationsMet() || loan.getStatus().isOverpaid())
                    && loan.isAllTranchesNotDisbursed()) {
                LocalDate submittedOnDate = loan.getSubmittedOnDate();
                if (DateUtils.isBefore(actualDisbursementDate, submittedOnDate)) {
                    final String errorMsg = "Loan can't be disbursed before " + submittedOnDate;
                    throw new LoanDisbursalException(errorMsg, "actualdisbursementdate.before.submittedDate", submittedOnDate,
                            actualDisbursementDate);
                }
            }

            LocalDate approvedOnDate = loan.getApprovedOnDate();
            if (actualDisbursementDate != null && DateUtils.isBefore(actualDisbursementDate, approvedOnDate)) {
                final String errorMessage = "The date on which a loan is disbursed cannot be before its approval date: " + approvedOnDate;
                throw new InvalidLoanStateTransitionException("disbursal", "cannot.be.before.approval.date", errorMessage,
                        actualDisbursementDate, approvedOnDate);
            }
        });
    }

    private void validateDisbursementWithPostDatedChecks(final String json, final Long loanId) {
        final JsonElement jsonElement = this.fromApiJsonHelper.parse(json);
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan.disbursement");
        final Loan loan = this.loanRepository.findById(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
        final List<LoanRepaymentScheduleInstallment> loanRepaymentScheduleInstallment = loan.getRepaymentScheduleInstallments();

        JsonObject jsonObject = jsonElement.getAsJsonObject();
        final Locale locale = this.fromApiJsonHelper.extractLocaleParameter(jsonObject);
        if (jsonObject.has("postDatedChecks") && jsonObject.get("postDatedChecks").isJsonArray()) {
            JsonArray postDatedChecks = jsonObject.get("postDatedChecks").getAsJsonArray();
            for (int i = 0; i < postDatedChecks.size(); i++) {
                final JsonObject postDatedCheck = postDatedChecks.get(i).getAsJsonObject();

                final String name = this.fromApiJsonHelper.extractStringNamed("name", postDatedCheck);
                baseDataValidator.reset().parameter("name").value(name).notNull();

                final BigDecimal amount = this.fromApiJsonHelper.extractBigDecimalNamed("amount", postDatedCheck, locale);
                baseDataValidator.reset().parameter("amount").value(amount).notNull().positiveAmount();

                final Long accountNo = this.fromApiJsonHelper.extractLongNamed("accountNo", postDatedCheck);
                baseDataValidator.reset().parameter("accountNo").value(accountNo).notNull().positiveAmount();

                final Long checkNo = this.fromApiJsonHelper.extractLongNamed("checkNo", postDatedCheck);
                baseDataValidator.reset().parameter("checkNo").value(checkNo).notNull().positiveAmount();

                final Integer installmentId = this.fromApiJsonHelper.extractIntegerNamed("installmentId", postDatedCheck, locale);
                final List<LoanRepaymentScheduleInstallment> installmentList = loanRepaymentScheduleInstallment.stream().filter(
                        repayment -> repayment.getInstallmentNumber().equals(installmentId) && repayment.getLoan().getId().equals(loanId))
                        .collect(Collectors.toList());
                if (installmentList.size() > 1) {
                    throw new PlatformDataIntegrityException("error.repayment.redundancy", "Multiple installment data found",
                            "postDatedChecks");
                } else if (installmentList.size() == 0) {
                    throw new LoanRepaymentScheduleNotFoundException(installmentId);
                }

            }

            if (!dataValidationErrors.isEmpty()) {
                throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.",
                        dataValidationErrors);
            }
        }
    }

    private void validateDisbursementDateWithMeetingDate(final LocalDate actualDisbursementDate, final CalendarInstance calendarInstance,
            Boolean isSkipRepaymentOnFirstMonth, Integer numberOfDays) {
        if (null != calendarInstance) {
            final Calendar calendar = calendarInstance.getCalendar();
            if (!calendar.isValidRecurringDate(actualDisbursementDate, isSkipRepaymentOnFirstMonth, numberOfDays)) {
                // Disbursement date should fall on a meeting date
                final String errorMessage = "Expected disbursement date '" + actualDisbursementDate.toString()
                        + "' does not fall on a meeting date.";
                throw new NotValidRecurringDateException("loan.actual.disbursement.date", errorMessage, actualDisbursementDate.toString(),
                        calendar.getTitle());
            }
        }
    }

    @Override
    public void validateUndoChargeOff(final String json) {
        if (!StringUtils.isBlank(json)) {
            final Set<String> transactionParameters = new HashSet<>(Arrays.asList(LoanApiConstants.REVERSAL_EXTERNAL_ID_PARAMNAME));
            final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
            this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, transactionParameters);

            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan.transaction");

            final JsonElement element = this.fromApiJsonHelper.parse(json);

            final String reversalExternalId = this.fromApiJsonHelper.extractStringNamed(LoanApiConstants.REVERSAL_EXTERNAL_ID_PARAMNAME,
                    element);
            baseDataValidator.reset().parameter(LoanApiConstants.REVERSAL_EXTERNAL_ID_PARAMNAME).ignoreIfNull().value(reversalExternalId)
                    .notExceedingLengthOf(100);

            throwExceptionIfValidationWarningsExist(dataValidationErrors);

        }
    }

    @Override
    public void validateTransaction(final String json) {

        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Set<String> transactionParameters = new HashSet<>(Arrays.asList("transactionDate", "transactionAmount", "externalId", "note",
                "locale", "dateFormat", "paymentTypeId", "accountNumber", "checkNumber", "routingCode", "receiptNumber", "bankNumber",
                LoanApiConstants.REVERSAL_EXTERNAL_ID_PARAMNAME));

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, transactionParameters);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan.transaction");

        final JsonElement element = this.fromApiJsonHelper.parse(json);
        final LocalDate transactionDate = this.fromApiJsonHelper.extractLocalDateNamed("transactionDate", element);
        baseDataValidator.reset().parameter("transactionDate").value(transactionDate).notNull();

        final BigDecimal transactionAmount = this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed("transactionAmount", element);
        baseDataValidator.reset().parameter("transactionAmount").value(transactionAmount).notNull().zeroOrPositiveAmount();

        final String note = this.fromApiJsonHelper.extractStringNamed("note", element);
        baseDataValidator.reset().parameter("note").value(note).notExceedingLengthOf(1000);

        final String reversalExternalId = this.fromApiJsonHelper.extractStringNamed(LoanApiConstants.REVERSAL_EXTERNAL_ID_PARAMNAME,
                element);
        baseDataValidator.reset().parameter(LoanApiConstants.REVERSAL_EXTERNAL_ID_PARAMNAME).ignoreIfNull().value(reversalExternalId)
                .notExceedingLengthOf(100);

        validatePaymentDetails(baseDataValidator, element);
        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    @Override
    public void validateChargebackTransaction(final String json) {

        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Set<String> transactionParameters = new HashSet<>(Arrays.asList(LoanApiConstants.TRANSACTION_AMOUNT_PARAMNAME,
                LoanApiConstants.localeParameterName, LoanApiConstants.externalIdParameterName, LoanApiConstants.noteParameterName,
                LoanApiConstants.PAYMENT_TYPE_PARAMNAME));

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, transactionParameters);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan.transaction");

        final JsonElement element = this.fromApiJsonHelper.parse(json);

        final BigDecimal transactionAmount = this.fromApiJsonHelper
                .extractBigDecimalWithLocaleNamed(LoanApiConstants.TRANSACTION_AMOUNT_PARAMNAME, element);
        baseDataValidator.reset().parameter(LoanApiConstants.TRANSACTION_AMOUNT_PARAMNAME).value(transactionAmount).notNull()
                .positiveAmount();

        final String note = this.fromApiJsonHelper.extractStringNamed(LoanApiConstants.noteParameterName, element);
        baseDataValidator.reset().parameter(LoanApiConstants.noteParameterName).value(note).notExceedingLengthOf(1000);

        validatePaymentDetails(baseDataValidator, element);
        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    @Override
    public void validateNewRepaymentTransaction(final String json) {
        validatePaymentTransaction(json);
    }

    @Override
    public void validateTransactionWithNoAmount(final String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Set<String> disbursementParameters = new HashSet<>(
                Arrays.asList("transactionDate", "note", "locale", "dateFormat", "writeoffReasonId", "externalId"));

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, disbursementParameters);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan.transaction");

        final JsonElement element = this.fromApiJsonHelper.parse(json);
        final LocalDate transactionDate = this.fromApiJsonHelper.extractLocalDateNamed("transactionDate", element);
        baseDataValidator.reset().parameter("transactionDate").value(transactionDate).notNull();

        final String note = this.fromApiJsonHelper.extractStringNamed("note", element);
        baseDataValidator.reset().parameter("note").value(note).notExceedingLengthOf(1000);

        final String externalId = this.fromApiJsonHelper.extractStringNamed("externalId", element);
        baseDataValidator.reset().parameter("externalId").value(externalId).ignoreIfNull().notExceedingLengthOf(100);

        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    @Override
    public void validateChargeOffTransaction(final String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Set<String> chargeOffParameters = new HashSet<>(
                Arrays.asList("transactionDate", "note", "locale", "dateFormat", "chargeOffReasonId", "externalId"));

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, chargeOffParameters);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan.transaction");

        final JsonElement element = fromApiJsonHelper.parse(json);
        final LocalDate transactionDate = fromApiJsonHelper.extractLocalDateNamed("transactionDate", element);
        baseDataValidator.reset().parameter("transactionDate").value(transactionDate).notNull();

        final String note = fromApiJsonHelper.extractStringNamed("note", element);
        baseDataValidator.reset().parameter("note").value(note).ignoreIfNull().notExceedingLengthOf(1000);

        final String externalId = fromApiJsonHelper.extractStringNamed("externalId", element);
        baseDataValidator.reset().parameter("externalId").value(externalId).ignoreIfNull().notExceedingLengthOf(100);

        final Long chargeOffReasonId = fromApiJsonHelper.extractLongNamed("chargeOffReasonId", element);
        baseDataValidator.reset().parameter("chargeOffReasonId").value(chargeOffReasonId).ignoreIfNull().integerGreaterThanZero();

        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    @Override
    public void validateUpdateOfLoanOfficer(final String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Set<String> disbursementParameters = new HashSet<>(
                Arrays.asList("assignmentDate", "fromLoanOfficerId", "toLoanOfficerId", "locale", "dateFormat"));

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, disbursementParameters);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loanOfficer");

        final JsonElement element = this.fromApiJsonHelper.parse(json);

        final Long toLoanOfficerId = this.fromApiJsonHelper.extractLongNamed("toLoanOfficerId", element);
        baseDataValidator.reset().parameter("toLoanOfficerId").value(toLoanOfficerId).notNull().integerGreaterThanZero();

        final String assignmentDateStr = this.fromApiJsonHelper.extractStringNamed("assignmentDate", element);
        baseDataValidator.reset().parameter("assignmentDate").value(assignmentDateStr).notBlank();

        if (!StringUtils.isBlank(assignmentDateStr)) {
            final LocalDate assignmentDate = this.fromApiJsonHelper.extractLocalDateNamed("assignmentDate", element);
            baseDataValidator.reset().parameter("assignmentDate").value(assignmentDate).notNull();
        }

        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    @Override
    public void validateForBulkLoanReassignment(final String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Set<String> supportedParameters = new HashSet<>(
                Arrays.asList("assignmentDate", "fromLoanOfficerId", "toLoanOfficerId", "loans", "locale", "dateFormat"));

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, supportedParameters);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loanOfficer");

        final JsonElement element = this.fromApiJsonHelper.parse(json);

        final LocalDate assignmentDate = this.fromApiJsonHelper.extractLocalDateNamed("assignmentDate", element);
        baseDataValidator.reset().parameter("assignmentDate").value(assignmentDate).notNull();
        final Long fromLoanOfficerId = this.fromApiJsonHelper.extractLongNamed("fromLoanOfficerId", element);
        baseDataValidator.reset().parameter("fromLoanOfficerId").value(fromLoanOfficerId).notNull().longGreaterThanZero();
        final Long toLoanOfficerId = this.fromApiJsonHelper.extractLongNamed("toLoanOfficerId", element);
        baseDataValidator.reset().parameter("toLoanOfficerId").value(toLoanOfficerId).notNull().longGreaterThanZero();
        final String[] loans = this.fromApiJsonHelper.extractArrayNamed("loans", element);
        baseDataValidator.reset().parameter("loans").value(loans).arrayNotEmpty();

        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    @Override
    public void validateMarkAsFraudLoan(final String json) {
        if (StringUtils.isBlank(json)) {
            return;
        }
        Set<String> transactionParameters = new HashSet<>(Arrays.asList(LoanApiConstants.FRAUD_ATTRIBUTE_NAME));

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, transactionParameters);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(LoanApiConstants.LOAN_FRAUD_DATAVALIDATOR_PREFIX);

        final JsonElement element = this.fromApiJsonHelper.parse(json);

        final boolean isFraud = this.fromApiJsonHelper.extractBooleanNamed(LoanApiConstants.FRAUD_ATTRIBUTE_NAME, element);
        baseDataValidator.reset().parameter(LoanApiConstants.FRAUD_ATTRIBUTE_NAME).value(isFraud).notNull();

        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    @Override
    public void validateUpdateDisbursementDateAndAmount(final String json, LoanDisbursementDetails loanDisbursementDetails) {

        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Set<String> disbursementParameters = new HashSet<>(Arrays.asList("locale", "dateFormat",
                LoanApiConstants.disbursementDataParameterName, LoanApiConstants.approvedLoanAmountParameterName,
                LoanApiConstants.updatedDisbursementDateParameterName, LoanApiConstants.updatedDisbursementPrincipalParameterName,
                LoanApiConstants.expectedDisbursementDateParameterName));

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, disbursementParameters);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan.update.disbursement");

        final JsonElement element = this.fromApiJsonHelper.parse(json);
        final LocalDate actualDisbursementDate = this.fromApiJsonHelper
                .extractLocalDateNamed(LoanApiConstants.expectedDisbursementDateParameterName, element);
        baseDataValidator.reset().parameter(LoanApiConstants.expectedDisbursementDateParameterName).value(actualDisbursementDate).notNull();

        final Locale locale = this.fromApiJsonHelper.extractLocaleParameter(element.getAsJsonObject());
        final BigDecimal principal = this.fromApiJsonHelper
                .extractBigDecimalNamed(LoanApiConstants.updatedDisbursementPrincipalParameterName, element, locale);
        baseDataValidator.reset().parameter(LoanApiConstants.disbursementPrincipalParameterName).value(principal).notNull();

        final BigDecimal approvedPrincipal = this.fromApiJsonHelper.extractBigDecimalNamed(LoanApiConstants.approvedLoanAmountParameterName,
                element, locale);
        if (loanDisbursementDetails.actualDisbursementDate() != null) {
            baseDataValidator.reset().parameter(LoanApiConstants.expectedDisbursementDateParameterName)
                    .failWithCode(LoanApiConstants.ALREADY_DISBURSED);
        }

        fromApiJsonDeserializer.validateLoanMultiDisbursementDate(element, baseDataValidator, actualDisbursementDate, approvedPrincipal);

        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    @Override
    public void validateNewRefundTransaction(final String json) {

        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Set<String> transactionParameters = new HashSet<>(Arrays.asList("transactionDate", "transactionAmount", "externalId", "note",
                "locale", "dateFormat", "paymentTypeId", "accountNumber", "checkNumber", "routingCode", "receiptNumber", "bankNumber"));

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, transactionParameters);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan.transaction");

        final JsonElement element = this.fromApiJsonHelper.parse(json);
        final LocalDate transactionDate = this.fromApiJsonHelper.extractLocalDateNamed("transactionDate", element);
        baseDataValidator.reset().parameter("transactionDate").value(transactionDate).notNull();

        final BigDecimal transactionAmount = this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed("transactionAmount", element);
        baseDataValidator.reset().parameter("transactionAmount").value(transactionAmount).notNull().positiveAmount();

        final String note = this.fromApiJsonHelper.extractStringNamed("note", element);
        baseDataValidator.reset().parameter("note").value(note).notExceedingLengthOf(1000);

        final String externalId = this.fromApiJsonHelper.extractStringNamed("externalId", element);
        baseDataValidator.reset().parameter("externalId").value(externalId).ignoreIfNull().notExceedingLengthOf(100);

        validatePaymentDetails(baseDataValidator, element);
        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    @Override
    public void validateLoanForeclosure(final String json) {

        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Set<String> foreclosureParameters = new HashSet<>(
                Arrays.asList("transactionDate", "note", "locale", "dateFormat", "externalId"));

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, foreclosureParameters);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan");

        final JsonElement element = this.fromApiJsonHelper.parse(json);
        final LocalDate transactionDate = this.fromApiJsonHelper.extractLocalDateNamed("transactionDate", element);
        baseDataValidator.reset().parameter("transactionDate").value(transactionDate).notNull();

        final String note = this.fromApiJsonHelper.extractStringNamed("note", element);
        baseDataValidator.reset().parameter("note").value(note).notExceedingLengthOf(1000);

        final String externalId = this.fromApiJsonHelper.extractStringNamed("externalId", element);
        baseDataValidator.reset().parameter("externalId").value(externalId).ignoreIfNull().notExceedingLengthOf(100);

        validatePaymentDetails(baseDataValidator, element);
        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    @Override
    public void validateLoanClientIsActive(final Loan loan) {
        final Client client = loan.client();
        if (client != null && client.isNotActive()) {
            throw new ClientNotActiveException(client.getId());
        }
    }

    @Override
    public void validateLoanGroupIsActive(final Loan loan) {
        final Group group = loan.group();
        if (group != null && group.isNotActive()) {
            throw new GroupNotActiveException(group.getId());
        }
    }

    private void validateLoanHasNoLaterChargeRefundTransactionToReverseOrCreateATransaction(Loan loan, LocalDate transactionDate,
            String reversedOrCreated) {
        for (LoanTransaction txn : loan.getLoanTransactions()) {
            if (txn.isChargeRefund() && DateUtils.isBefore(transactionDate, txn.getTransactionDate())) {
                final String errorMessage = "loan.transaction.cant.be." + reversedOrCreated + ".because.later.charge.refund.exists";
                final String details = "Loan Transaction: " + loan.getId() + " Can't be " + reversedOrCreated
                        + " because a Later Charge Refund Exists.";
                throw new LoanChargeRefundException(errorMessage, details);
            }
        }
    }

    private void validateLoanDisbursementIsBeforeTransactionDate(final Loan loan, final LocalDate transactionDate) {
        if (DateUtils.isBefore(transactionDate, loan.getDisbursementDate())) {
            final String errorMessage = "The transaction date cannot be before the loan disbursement date: "
                    + loan.getDisbursementDate().toString();
            throw new InvalidLoanStateTransitionException("transaction", "cannot.be.before.disbursement.date", errorMessage,
                    transactionDate, loan.getDisbursementDate());
        }
    }

    private void validateTransactionShouldNotBeInTheFuture(final LocalDate transactionDate) {
        if (DateUtils.isDateInTheFuture(transactionDate)) {
            final String errorMessage = "The transaction date cannot be in the future.";
            throw new InvalidLoanStateTransitionException("transaction", "cannot.be.a.future.date", errorMessage, transactionDate);
        }
    }

    private void validateLoanHasCurrency(final Loan loan) {
        MonetaryCurrency currency = loan.getCurrency();
        final ApplicationCurrency defaultApplicationCurrency = this.applicationCurrencyRepository.findOneByCode(currency.getCode());
        if (defaultApplicationCurrency == null) {
            throw new CurrencyNotFoundException(currency.getCode());
        }
    }

    private void validateClientOfficeJoiningDateIsBeforeTransactionDate(Loan loan, LocalDate transactionDate) {
        if (loan.getClient() != null && loan.getClient().getOfficeJoiningDate() != null) {
            final LocalDate clientOfficeJoiningDate = loan.getClient().getOfficeJoiningDate();
            if (DateUtils.isBefore(transactionDate, clientOfficeJoiningDate)) {
                String errorMessage = "The date on which a repayment or waiver is made cannot be earlier than client's transfer date to this office";
                String action = "repayment.or.waiver";
                String postfix = "cannot.be.made.before.client.transfer.date";
                throw new InvalidLoanStateTransitionException(action, postfix, errorMessage, clientOfficeJoiningDate);
            }
        }
    }

    @Override
    public void validateActivityNotBeforeLastTransactionDate(final Loan loan, final LocalDate activityDate, final LoanEvent event) {
        if (!(loan.isInterestBearingAndInterestRecalculationEnabled() || loan.loanProduct().isHoldGuaranteeFunds())
                || !loan.getLoanRepaymentScheduleDetail().getLoanScheduleType().equals(LoanScheduleType.CUMULATIVE)) {
            return;
        }
        LocalDate lastTransactionDate = loan.getLastUserTransactionDate();
        if (DateUtils.isAfter(lastTransactionDate, activityDate)) {
            String errorMessage = null;
            String action = null;
            String postfix = null;
            switch (event) {
                case LOAN_REPAYMENT_OR_WAIVER -> {
                    errorMessage = "The date on which a repayment or waiver is made cannot be earlier than last transaction date";
                    action = "repayment.or.waiver";
                    postfix = "cannot.be.made.before.last.transaction.date";
                }
                case WRITE_OFF_OUTSTANDING -> {
                    errorMessage = "The date on which a write off is made cannot be earlier than last transaction date";
                    action = "writeoff";
                    postfix = "cannot.be.made.before.last.transaction.date";
                }
                case LOAN_CHARGE_PAYMENT -> {
                    errorMessage = "The date on which a charge payment is made cannot be earlier than last transaction date";
                    action = "charge.payment";
                    postfix = "cannot.be.made.before.last.transaction.date";
                }
                default -> {
                }
            }
            throw new InvalidLoanStateTransitionException(action, postfix, errorMessage, lastTransactionDate);
        }
    }

    @Override
    public void validateRepaymentDateIsOnNonWorkingDay(final LocalDate repaymentDate, final WorkingDays workingDays,
            final boolean allowTransactionsOnNonWorkingDay) {
        if (!allowTransactionsOnNonWorkingDay && !WorkingDaysUtil.isWorkingDay(workingDays, repaymentDate)) {
            final String errorMessage = "Repayment date cannot be on a non working day";
            throw new LoanApplicationDateException("repayment.date.on.non.working.day", errorMessage, repaymentDate);
        }
    }

    @Override
    public void validateRepaymentDateIsOnHoliday(final LocalDate repaymentDate, final boolean allowTransactionsOnHoliday,
            final List<Holiday> holidays) {
        if (!allowTransactionsOnHoliday && HolidayUtil.isHoliday(repaymentDate, holidays)) {
            final String errorMessage = "Repayment date cannot be on a holiday";
            throw new LoanApplicationDateException("repayment.date.on.holiday", errorMessage, repaymentDate);
        }
    }

    private void validateTransactionAmountNotExceedThresholdForMultiDisburseLoan(Loan loan) {
        if (loan.getLoanProduct().isMultiDisburseLoan()) {
            BigDecimal totalDisbursed = loan.getDisbursedAmount();
            BigDecimal totalPrincipalAdjusted = loan.getSummary().getTotalPrincipalAdjustments();
            BigDecimal totalPrincipalCredited = totalDisbursed.add(totalPrincipalAdjusted);
            if (totalPrincipalCredited.compareTo(loan.getSummary().getTotalPrincipalRepaid()) < 0) {
                final String errorMessage = "The transaction amount cannot exceed threshold.";
                throw new InvalidLoanStateTransitionException("transaction", "amount.exceeds.threshold", errorMessage);
            }
        }
    }

    @Override
    public void validateLoanTransactionInterestPaymentWaiver(JsonCommand command) {
        final Long loanId = command.getLoanId();
        Loan loan = this.loanRepository.findById(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        validateNewRepaymentTransaction(command.json());
        validateTransactionShouldNotBeInTheFuture(transactionDate);
        validateLoanClientIsActive(loan);
        validateLoanHasCurrency(loan);
        validateLoanGroupIsActive(loan);
        loanDownPaymentTransactionValidator.validateLoanStatusIsActiveOrFullyPaidOrOverpaid(loan);
        validateLoanDisbursementIsBeforeTransactionDate(loan, transactionDate);
        validateLoanHasNoLaterChargeRefundTransactionToReverseOrCreateATransaction(loan, transactionDate, "created");

        validateClientOfficeJoiningDateIsBeforeTransactionDate(loan, transactionDate);
        validateActivityNotBeforeLastTransactionDate(loan, transactionDate, LoanEvent.LOAN_REPAYMENT_OR_WAIVER);
        HolidayDetailDTO holidayDetailDTO = loanUtilService.constructHolidayDTO(loan.getOfficeId(), loan.getDisbursementDate());
        validateRepaymentDateIsOnHoliday(transactionDate, holidayDetailDTO.isAllowTransactionsOnHoliday(), holidayDetailDTO.getHolidays());
        validateRepaymentDateIsOnNonWorkingDay(transactionDate, holidayDetailDTO.getWorkingDays(),
                holidayDetailDTO.isAllowTransactionsOnNonWorkingDay());
        validateTransactionAmountNotExceedThresholdForMultiDisburseLoan(loan);
    }

    @Override
    public void validateLoanTransactionInterestPaymentWaiverAfterRecalculation(Loan loan) {
        // Payment allocation calculates the new total principal repaid, and it should be validated after recalculation
        if (loan.getLoanProduct().isMultiDisburseLoan()) {
            BigDecimal totalDisbursed = loan.getDisbursedAmount();
            BigDecimal totalPrincipalAdjusted = loan.getSummary().getTotalPrincipalAdjustments();
            BigDecimal totalPrincipalCredited = totalDisbursed.add(totalPrincipalAdjusted);
            if (totalPrincipalCredited.compareTo(loan.getSummary().getTotalPrincipalRepaid()) < 0
                    && loan.getLoanRepaymentScheduleDetail().getPrincipal().minus(totalDisbursed).isGreaterThanZero()) {
                final String errorMessage = "The transaction amount cannot exceed threshold.";
                throw new InvalidLoanStateTransitionException("transaction", "amount.exceeds.threshold", errorMessage);
            }
        }
    }

    @Override
    public void validateRefund(String json) {
        validatePaymentTransaction(json);
    }

    @Override
    public void validateRefund(final Loan loan, LoanTransactionType loanTransactionType, final LocalDate transactionDate,
            ScheduleGeneratorDTO scheduleGeneratorDTO) {
        checkClientOrGroupActive(loan);
        loanDownPaymentTransactionValidator.validateLoanStatusIsActiveOrFullyPaidOrOverpaid(loan);
        validateActivityNotBeforeClientOrGroupTransferDate(loan, transactionDate);
        validateRepaymentTypeTransactionNotBeforeAChargeRefund(loan, loanTransactionType, transactionDate);
        validateTransactionNotBeforeLastTransactionDate(loan, loanTransactionType, transactionDate);
        validateRepaymentDateIsOnHoliday(transactionDate, scheduleGeneratorDTO.getHolidayDetailDTO().isAllowTransactionsOnHoliday(),
                scheduleGeneratorDTO.getHolidayDetailDTO().getHolidays());
        validateRepaymentDateIsOnNonWorkingDay(transactionDate, scheduleGeneratorDTO.getHolidayDetailDTO().getWorkingDays(),
                scheduleGeneratorDTO.getHolidayDetailDTO().isAllowTransactionsOnNonWorkingDay());
        validateTransactionShouldNotBeInTheFuture(transactionDate);
        validateTransactionAmountNotExceedThresholdForMultiDisburseLoan(loan);
    }

    private void validateRepaymentTypeTransactionNotBeforeAChargeRefund(final Loan loan, final LoanTransactionType loanTransactionType,
            final LocalDate transactionDate) {
        if (loanTransactionType.isRepaymentType() && !loanTransactionType.isChargeRefund()) {
            for (LoanTransaction txn : loan.getLoanTransactions()) {
                if (txn.isChargeRefund() && DateUtils.isBefore(transactionDate, txn.getTransactionDate())) {
                    final String errorMessage = "loan.transaction.cant.be.created.because.later.charge.refund.exists";
                    final String details = "Loan Transaction: " + loan.getId() + " Can't be created because a Later Charge Refund Exists.";
                    throw new LoanChargeRefundException(errorMessage, details);
                }
            }
        }
    }

    @Override
    public void validateRefundDateIsAfterLastRepayment(final Loan loan, final LocalDate refundTransactionDate) {
        final LocalDate possibleNextRefundDate = loan.possibleNextRefundDate();

        if (possibleNextRefundDate == null || DateUtils.isBefore(refundTransactionDate, possibleNextRefundDate)) {
            throw new InvalidRefundDateException(refundTransactionDate.toString());
        }
    }

    @Override
    public void validateActivityNotBeforeClientOrGroupTransferDate(final Loan loan, final LoanEvent event, final LocalDate activityDate) {
        if (loan.getClient() != null && loan.getClient().getOfficeJoiningDate() != null) {
            final LocalDate clientOfficeJoiningDate = loan.getClient().getOfficeJoiningDate();
            if (DateUtils.isBefore(activityDate, clientOfficeJoiningDate)) {
                String errorMessage = null;
                String action = null;
                String postfix = null;
                switch (event) {
                    case LOAN_APPROVED -> {
                        errorMessage = "The date on which a loan is approved cannot be earlier than client's transfer date to this office";
                        action = "approval";
                        postfix = "cannot.be.before.client.transfer.date";
                    }
                    case LOAN_APPROVAL_UNDO -> {
                        errorMessage = "The date on which a loan is approved cannot be earlier than client's transfer date to this office";
                        action = "approval";
                        postfix = "cannot.be.undone.before.client.transfer.date";
                    }
                    case LOAN_DISBURSED -> {
                        errorMessage = "The date on which a loan is disbursed cannot be earlier than client's transfer date to this office";
                        action = "disbursal";
                        postfix = "cannot.be.before.client.transfer.date";
                    }
                    case LOAN_DISBURSAL_UNDO -> {
                        errorMessage = "Cannot undo a disbursal done in another branch";
                        action = "disbursal";
                        postfix = "cannot.be.undone.before.client.transfer.date";
                    }
                    case LOAN_REPAYMENT_OR_WAIVER -> {
                        errorMessage = "The date on which a repayment or waiver is made cannot be earlier than client's transfer date to this office";
                        action = "repayment.or.waiver";
                        postfix = "cannot.be.made.before.client.transfer.date";
                    }
                    case WRITE_OFF_OUTSTANDING -> {
                        errorMessage = "The date on which a write off is made cannot be earlier than client's transfer date to this office";
                        action = "writeoff";
                        postfix = "cannot.be.undone.before.client.transfer.date";
                    }
                    case REPAID_IN_FULL -> {
                        errorMessage = "The date on which the loan is repaid in full cannot be earlier than client's transfer date to this office";
                        action = "close";
                        postfix = "cannot.be.undone.before.client.transfer.date";
                    }
                    case LOAN_CHARGE_PAYMENT -> {
                        errorMessage = "The date on which a charge payment is made cannot be earlier than client's transfer date to this office";
                        action = "charge.payment";
                        postfix = "cannot.be.made.before.client.transfer.date";
                    }
                    case LOAN_REFUND -> {
                        errorMessage = "The date on which a refund is made cannot be earlier than client's transfer date to this office";
                        action = "refund";
                        postfix = "cannot.be.made.before.client.transfer.date";
                    }
                    case LOAN_DISBURSAL_UNDO_LAST -> {
                        errorMessage = "Cannot undo a last disbursal in another branch";
                        action = "disbursal";
                        postfix = "cannot.be.undone.before.client.transfer.date";
                    }
                    default -> {
                    }
                }
                throw new InvalidLoanStateTransitionException(action, postfix, errorMessage, clientOfficeJoiningDate);
            }
        }
    }

    private static @NonNull BigDecimal collectTotalCollateral(Set<LoanCollateralManagement> loanCollateralManagements) {
        BigDecimal totalCollateral = BigDecimal.ZERO;

        for (LoanCollateralManagement loanCollateralManagement : loanCollateralManagements) {
            BigDecimal quantity = loanCollateralManagement.getQuantity();
            BigDecimal pctToBase = loanCollateralManagement.getClientCollateralManagement().getCollaterals().getPctToBase();
            BigDecimal basePrice = loanCollateralManagement.getClientCollateralManagement().getCollaterals().getBasePrice();
            totalCollateral = totalCollateral.add(quantity.multiply(basePrice).multiply(pctToBase).divide(BigDecimal.valueOf(100)));
        }
        return totalCollateral;
    }

    private static @NonNull Set<String> getDisbursementParameters(boolean isAccountTransfer) {
        Set<String> disbursementParameters;

        if (isAccountTransfer) {
            disbursementParameters = new HashSet<>(Arrays.asList("actualDisbursementDate", "externalId", "note", "locale", "dateFormat",
                    LoanApiConstants.principalDisbursedParameterName, LoanApiConstants.fixedEmiAmountParameterName,
                    LoanApiConstants.disbursementNetDisbursalAmountParameterName));
        } else {
            disbursementParameters = new HashSet<>(Arrays.asList("actualDisbursementDate", "externalId", "note", "locale", "dateFormat",
                    "paymentTypeId", "accountNumber", "checkNumber", "routingCode", "receiptNumber", "bankNumber", "adjustRepaymentDate",
                    LoanApiConstants.principalDisbursedParameterName, LoanApiConstants.fixedEmiAmountParameterName,
                    LoanApiConstants.postDatedChecks, LoanApiConstants.disbursementNetDisbursalAmountParameterName));
        }
        return disbursementParameters;
    }

    private void validatePaymentTransaction(String json) {
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Set<String> transactionParameters = new HashSet<>(
                Arrays.asList("transactionDate", "transactionAmount", "externalId", "note", "locale", "dateFormat", "paymentTypeId",
                        "accountNumber", "checkNumber", "routingCode", "receiptNumber", "bankNumber", "loanId", "numberOfRepayments"));

        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, transactionParameters);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan.transaction");

        final JsonElement element = this.fromApiJsonHelper.parse(json);
        final LocalDate transactionDate = this.fromApiJsonHelper.extractLocalDateNamed("transactionDate", element);
        baseDataValidator.reset().parameter("transactionDate").value(transactionDate).notNull();

        final BigDecimal transactionAmount = this.fromApiJsonHelper.extractBigDecimalWithLocaleNamed("transactionAmount", element);
        baseDataValidator.reset().parameter("transactionAmount").value(transactionAmount).notNull().positiveAmount();

        final String note = this.fromApiJsonHelper.extractStringNamed("note", element);
        baseDataValidator.reset().parameter("note").value(note).notExceedingLengthOf(1000);

        validatePaymentDetails(baseDataValidator, element);
        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    @Override
    public void validatePaymentDetails(final DataValidatorBuilder baseDataValidator, final JsonElement element) {
        // Validate all string payment detail fields for max length
        final Integer paymentTypeId = this.fromApiJsonHelper.extractIntegerWithLocaleNamed("paymentTypeId", element);

        baseDataValidator.reset().parameter("paymentTypeId").value(paymentTypeId).ignoreIfNull().integerGreaterThanZero();

        final Set<String> paymentDetailParameters = new HashSet<>(
                Arrays.asList("accountNumber", "checkNumber", "routingCode", "receiptNumber", "bankNumber"));
        for (final String paymentDetailParameterName : paymentDetailParameters) {
            final String paymentDetailParameterValue = this.fromApiJsonHelper.extractStringNamed(paymentDetailParameterName, element);
            baseDataValidator.reset().parameter(paymentDetailParameterName).value(paymentDetailParameterValue).ignoreIfNull()
                    .notExceedingLengthOf(50);
        }
    }

    private void checkClientOrGroupActive(final Loan loan) {
        final Client client = loan.client();
        if (client != null && client.isNotActive()) {
            throw new ClientNotActiveException(client.getId());
        }
        final Group group = loan.group();
        if (group != null && group.isNotActive()) {
            throw new GroupNotActiveException(group.getId());
        }
    }

    private void validateActivityNotBeforeClientOrGroupTransferDate(final Loan loan, final LocalDate transactionDate) {
        if (loan.getClient() != null && loan.getClient().getOfficeJoiningDate() != null) {
            final LocalDate clientOfficeJoiningDate = loan.getClient().getOfficeJoiningDate();
            if (DateUtils.isBefore(transactionDate, clientOfficeJoiningDate)) {
                String errorMessage = "The date on which the transaction is made cannot be earlier than client's transfer date to this office";
                String action = "repayment.or.waiver";
                String postfix = "cannot.be.made.before.client.transfer.date";
                throw new InvalidLoanStateTransitionException(action, postfix, errorMessage, clientOfficeJoiningDate);
            }
        }
    }

    private void validateTransactionNotBeforeLastTransactionDate(final Loan loan, LoanTransactionType loanTransactionType,
            final LocalDate transactionDate) {
        if (!((LoanScheduleType.CUMULATIVE.equals(loan.getLoanProductRelatedDetail().getLoanScheduleType())
                && loan.isInterestBearingAndInterestRecalculationEnabled()) || loan.getLoanProduct().isHoldGuaranteeFunds())) {
            return;
        }
        LocalDate lastTransactionDate = loan.getLastUserTransactionDate();

        String humanReadable;
        String action;
        switch (loanTransactionType) {
            case MERCHANT_ISSUED_REFUND -> {
                humanReadable = "merchant issued refund";
                action = "merchant.issued.refund";
            }
            case PAYOUT_REFUND -> {
                humanReadable = "payout refund";
                action = "payout.refund";
            }
            default -> {
                humanReadable = "transaction";
                action = "transaction";
            }
        }
        if (DateUtils.isAfter(lastTransactionDate, transactionDate)) {
            String errorMessage = "The date on which the " + humanReadable + " is made cannot be earlier than last transaction date";
            String postfix = "cannot.be.made.before.last.transaction.date";
            throw new InvalidLoanStateTransitionException(action, postfix, errorMessage, lastTransactionDate);
        }
    }

    @Override
    public void validateIfTransactionIsChargeback(final LoanTransaction chargebackTransaction) {
        if (!chargebackTransaction.isChargeback()) {
            final String errorMessage = "A transaction of type chargeback was expected but not received.";
            throw new InvalidLoanTransactionTypeException("transaction", "is.not.a.chargeback.transaction", errorMessage);
        }
    }

    @Override
    public void validateLoanRescheduleDate(final Loan loan) {
        if (DateUtils.isBefore(loan.getRescheduledOnDate(), loan.getDisbursementDate())) {
            final String errorMessage = "The date on which a loan is rescheduled cannot be before the loan disbursement date: "
                    + loan.getDisbursementDate().toString();
            throw new InvalidLoanStateTransitionException("close.reschedule", "cannot.be.before.submittal.date", errorMessage,
                    loan.getRescheduledOnDate(), loan.getDisbursementDate());
        }

        if (DateUtils.isDateInTheFuture(loan.getRescheduledOnDate())) {
            final String errorMessage = "The date on which a loan is rescheduled cannot be in the future.";
            throw new InvalidLoanStateTransitionException("close.reschedule", "cannot.be.a.future.date", errorMessage,
                    loan.getRescheduledOnDate());
        }
    }

    @Override
    public void validateNote(final DataValidatorBuilder baseDataValidator, final JsonElement element) {
        final String note = this.fromApiJsonHelper.extractStringNamed("note", element);
        if (StringUtils.isNotBlank(note)) {
            baseDataValidator.reset().parameter("note").value(note).notExceedingLengthOf(1000);
        }
    }

    @Override
    public void validateExternalId(final DataValidatorBuilder baseDataValidator, final JsonElement element) {
        final String externalId = this.fromApiJsonHelper.extractStringNamed("externalId", element);
        if (StringUtils.isNotBlank(externalId)) {
            baseDataValidator.reset().parameter("externalId").value(externalId).notExceedingLengthOf(100);
        }
    }

    @Override
    public void validateReversalExternalId(final DataValidatorBuilder baseDataValidator, final JsonElement element) {
        final String reversalExternalId = this.fromApiJsonHelper.extractStringNamed("reversalExternalId", element);
        if (StringUtils.isNotBlank(reversalExternalId)) {
            baseDataValidator.reset().parameter("reversalExternalId").value(reversalExternalId).notExceedingLengthOf(100);
        }
    }
}
