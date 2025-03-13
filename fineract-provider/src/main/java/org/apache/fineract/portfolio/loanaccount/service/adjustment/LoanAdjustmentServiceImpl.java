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
package org.apache.fineract.portfolio.loanaccount.service.adjustment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanAdjustTransactionBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanBalanceChangedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanEvent;
import org.apache.fineract.portfolio.loanaccount.domain.LoanLifecycleStateMachine;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallmentRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelationTypeEnum;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.exception.InvalidLoanTransactionTypeException;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanTransactionValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualTransactionBusinessEventService;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.portfolio.loanaccount.service.LoanDownPaymentHandlerService;
import org.apache.fineract.portfolio.loanaccount.service.LoanJournalEntryPoster;
import org.apache.fineract.portfolio.loanaccount.service.LoanUtilService;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class LoanAdjustmentServiceImpl implements LoanAdjustmentService {

    private final LoanTransactionValidator loanTransactionValidator;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final LoanAccountDomainService loanAccountDomainService;
    private final NoteRepository noteRepository;
    private final LoanTransactionRepository loanTransactionRepository;
    private final PaymentDetailWritePlatformService paymentDetailWritePlatformService;
    private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final LoanUtilService loanUtilService;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanLifecycleStateMachine loanLifecycleStateMachine;
    private final LoanAccrualTransactionBusinessEventService loanAccrualTransactionBusinessEventService;
    private final LoanDownPaymentHandlerService loanDownPaymentHandlerService;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;
    private final LoanChargeValidator loanChargeValidator;
    private final LoanJournalEntryPoster journalEntryPoster;

    @Override
    public CommandProcessingResult adjustLoanTransaction(Loan loan, LoanTransaction transactionToAdjust, LoanAdjustmentParameter parameter,
            Long commandId, Map<String, Object> changes) {
        LocalDate transactionDate = parameter.getTransactionDate();
        BigDecimal transactionAmount = parameter.getTransactionAmount();
        PaymentDetail paymentDetail = parameter.getPaymentDetail();
        ExternalId txnExternalId = parameter.getTxnExternalId();
        ExternalId reversalTxnExternalId = parameter.getReversalTxnExternalId();
        String noteText = parameter.getNoteText();

        final List<Long> existingTransactionIds = new ArrayList<>();
        final List<Long> existingReversedTransactionIds = new ArrayList<>();

        final Money transactionAmountAsMoney = Money.of(loan.getCurrency(), transactionAmount);
        LoanTransaction newTransactionDetail = LoanTransaction.repaymentType(transactionToAdjust.getTypeOf(), loan.getOffice(),
                transactionAmountAsMoney, paymentDetail, transactionDate, txnExternalId, transactionToAdjust.getChargeRefundChargeType());
        if (transactionToAdjust.isInterestWaiver()) {
            Money unrecognizedIncome = transactionAmountAsMoney.zero();
            Money interestComponent = transactionAmountAsMoney;
            if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
                Money receivableInterest = loan.getReceivableInterest(transactionDate);
                if (transactionAmountAsMoney.isGreaterThan(receivableInterest)) {
                    interestComponent = receivableInterest;
                    unrecognizedIncome = transactionAmountAsMoney.minus(receivableInterest);
                }
            }
            newTransactionDetail = LoanTransaction.waiver(loan.getOffice(), loan, transactionAmountAsMoney, transactionDate,
                    interestComponent, unrecognizedIncome, txnExternalId);
        }

        LocalDate recalculateFrom = null;

        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            recalculateFrom = DateUtils.isAfter(transactionToAdjust.getTransactionDate(), transactionDate) ? transactionDate
                    : transactionToAdjust.getTransactionDate();
        }

        ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);

        HolidayDetailDTO holidayDetailDTO = scheduleGeneratorDTO.getHolidayDetailDTO();
        if (loan.getLoanRepaymentScheduleDetail().getLoanScheduleType().equals(LoanScheduleType.CUMULATIVE)) {
            // validate cumulative
            loanTransactionValidator.validateActivityNotBeforeLastTransactionDate(loan, transactionToAdjust.getTransactionDate(),
                    LoanEvent.LOAN_REPAYMENT_OR_WAIVER);
        }
        // common validations
        loanTransactionValidator.validateRepaymentDateIsOnHoliday(newTransactionDetail.getTransactionDate(),
                holidayDetailDTO.isAllowTransactionsOnHoliday(), holidayDetailDTO.getHolidays());
        loanTransactionValidator.validateRepaymentDateIsOnNonWorkingDay(newTransactionDetail.getTransactionDate(),
                holidayDetailDTO.getWorkingDays(), holidayDetailDTO.isAllowTransactionsOnNonWorkingDay());

        adjustExistingTransaction(loan, newTransactionDetail, loanLifecycleStateMachine, transactionToAdjust, existingTransactionIds,
                existingReversedTransactionIds, scheduleGeneratorDTO, reversalTxnExternalId);

        loanAccrualsProcessingService.reprocessExistingAccruals(loan);
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan);
        }

        boolean thereIsNewTransaction = newTransactionDetail.isGreaterThanZero();
        if (thereIsNewTransaction) {
            if (paymentDetail != null) {
                this.paymentDetailWritePlatformService.persistPaymentDetail(paymentDetail);
            }
            this.loanTransactionRepository.saveAndFlush(newTransactionDetail);
        }

        loan = saveAndFlushLoanWithDataIntegrityViolationChecks(loan);

        if (StringUtils.isNotBlank(noteText)) {
            changes.put("note", noteText);
            Note note;
            /**
             * If a new transaction is not created, associate note with the transaction to be adjusted
             **/
            if (thereIsNewTransaction) {
                note = Note.loanTransactionNote(loan, newTransactionDetail, noteText);
            } else {
                note = Note.loanTransactionNote(loan, transactionToAdjust, noteText);
            }
            this.noteRepository.save(note);
        }

        Collection<Long> transactionIds = new ArrayList<>();
        List<LoanTransaction> transactions = loan.getLoanTransactions();
        for (LoanTransaction transaction : transactions) {
            if (transaction.isRefund() && transaction.isNotReversed()) {
                transactionIds.add(transaction.getId());
            }
        }

        if (!transactionIds.isEmpty()) {
            this.accountTransfersWritePlatformService.reverseTransfersWithFromAccountTransactions(transactionIds,
                    PortfolioAccountType.LOAN);
            loan.updateLoanSummaryAndStatus();
        }

        loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, loan.isInterestBearingAndInterestRecalculationEnabled(),
                false);

        this.loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());

        LoanAdjustTransactionBusinessEvent.Data eventData = new LoanAdjustTransactionBusinessEvent.Data(transactionToAdjust);
        if (newTransactionDetail.isRepaymentLikeType() && thereIsNewTransaction) {
            eventData.setNewTransactionDetail(newTransactionDetail);
        }
        Long entityId = transactionToAdjust.getId();
        ExternalId entityExternalId = transactionToAdjust.getExternalId();

        if (thereIsNewTransaction) {
            entityId = newTransactionDetail.getId();
            entityExternalId = newTransactionDetail.getExternalId();
        }
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanAdjustTransactionBusinessEvent(eventData));

        journalEntryPoster.postJournalEntries(loan, existingTransactionIds, existingReversedTransactionIds);
        loanAccrualTransactionBusinessEventService.raiseBusinessEventForAccrualTransactions(loan, existingTransactionIds);

        return new CommandProcessingResultBuilder() //
                .withCommandId(commandId) //
                .withEntityId(entityId) //
                .withEntityExternalId(entityExternalId) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loan.getId()) //
                .with(changes).build();
    }

    public void adjustExistingTransaction(final Loan loan, final LoanTransaction newTransactionDetail,
            final LoanLifecycleStateMachine loanLifecycleStateMachine, final LoanTransaction transactionForAdjustment,
            final List<Long> existingTransactionIds, final List<Long> existingReversedTransactionIds,
            final ScheduleGeneratorDTO scheduleGeneratorDTO, final ExternalId reversalExternalId) {
        existingTransactionIds.addAll(loan.findExistingTransactionIds());
        existingReversedTransactionIds.addAll(loan.findExistingReversedTransactionIds());

        loanTransactionValidator.validateActivityNotBeforeClientOrGroupTransferDate(loan, LoanEvent.LOAN_REPAYMENT_OR_WAIVER,
                transactionForAdjustment.getTransactionDate());

        if (!transactionForAdjustment.isAccrualRelated() && transactionForAdjustment.isNotRepaymentLikeType()
                && transactionForAdjustment.isNotWaiver() && transactionForAdjustment.isNotCreditBalanceRefund()) {
            final String errorMessage = "Only (non-reversed) transactions of type repayment, waiver, accrual or credit balance refund can be adjusted.";
            throw new InvalidLoanTransactionTypeException("transaction",
                    "adjustment.is.only.allowed.to.repayment.or.waiver.or.creditbalancerefund.transactions", errorMessage);
        }

        loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(transactionForAdjustment.getLoan(),
                transactionForAdjustment, "reversed");
        transactionForAdjustment.reverse(reversalExternalId);
        transactionForAdjustment.manuallyAdjustedOrReversed();

        if (transactionForAdjustment.getTypeOf().equals(LoanTransactionType.MERCHANT_ISSUED_REFUND)
                || transactionForAdjustment.getTypeOf().equals(LoanTransactionType.PAYOUT_REFUND)) {
            loan.getLoanTransactions().stream() //
                    .filter(LoanTransaction::isNotReversed)
                    .filter(loanTransaction -> loanTransaction.getLoanTransactionRelations().stream()
                            .anyMatch(relation -> relation.getRelationType().equals(LoanTransactionRelationTypeEnum.RELATED)
                                    && relation.getToTransaction().getId().equals(transactionForAdjustment.getId())))
                    .forEach(loanTransaction -> {
                        loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(loanTransaction.getLoan(),
                                loanTransaction, "reversed");
                        loanTransaction.reverse();
                        loanTransaction.manuallyAdjustedOrReversed();
                        LoanAdjustTransactionBusinessEvent.Data eventData = new LoanAdjustTransactionBusinessEvent.Data(loanTransaction);
                        businessEventNotifierService.notifyPostBusinessEvent(new LoanAdjustTransactionBusinessEvent(eventData));
                    });
        }

        if (loan.isClosedWrittenOff()) {
            // find write off transaction and reverse it
            final LoanTransaction writeOffTransaction = loan.findWriteOffTransaction();
            loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(writeOffTransaction.getLoan(), writeOffTransaction,
                    "reversed");
            writeOffTransaction.reverse();
        }

        loan.updateLoanSummaryAndStatus();

        if (newTransactionDetail.isRepaymentLikeType() || newTransactionDetail.isInterestWaiver()) {
            loanDownPaymentHandlerService.handleRepaymentOrRecoveryOrWaiverTransaction(loan, newTransactionDetail,
                    loanLifecycleStateMachine, transactionForAdjustment, scheduleGeneratorDTO);
        }
    }

    private Loan saveAndFlushLoanWithDataIntegrityViolationChecks(final Loan loan) {
        /*
         * Due to the "saveAndFlushLoanWithDataIntegrityViolationChecks" method the loan is saved and flushed in the
         * middle of the transaction. EclipseLink is in some situations are saving inconsistently the newly created
         * associations, like the newly created repayment schedule installments. The save and flush cannot be removed
         * safely till any native queries are used as part of this transaction either. See:
         * this.loanAccountDomainService.recalculateAccruals(loan);
         */
        try {
            loanRepaymentScheduleInstallmentRepository.saveAll(loan.getRepaymentScheduleInstallments());
            return this.loanRepositoryWrapper.saveAndFlush(loan);
        } catch (final JpaSystemException | DataIntegrityViolationException e) {
            final Throwable realCause = e.getCause();
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan.transaction");
            if (realCause.getMessage().toLowerCase().contains("external_id_unique")) {
                baseDataValidator.reset().parameter(LoanApiConstants.externalIdParameterName).failWithCode("value.must.be.unique");
            }
            if (!dataValidationErrors.isEmpty()) {
                throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.",
                        dataValidationErrors, e);
            }
            throw e;
        }
    }
}
