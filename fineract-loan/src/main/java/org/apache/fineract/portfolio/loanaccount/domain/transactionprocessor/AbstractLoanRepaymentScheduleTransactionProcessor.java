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
package org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor;

import static java.math.BigDecimal.ZERO;
import static org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction.accrualAdjustment;
import static org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction.accrueTransaction;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.data.LoanChargePaidDetail;
import org.apache.fineract.portfolio.loanaccount.data.TransactionChangeData;
import org.apache.fineract.portfolio.loanaccount.domain.ChangedTransactionDetail;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeOffBehaviour;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInstallmentCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelation;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelationTypeEnum;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionToRepaymentScheduleMapping;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.domain.SingleLoanChargeRepaymentScheduleProcessingWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl.CreocoreLoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl.HeavensFamilyLoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl.InterestPrincipalPenaltyFeesOrderLoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
import org.springframework.util.CollectionUtils;

/**
 * Abstract implementation of {@link LoanRepaymentScheduleTransactionProcessor} which is more convenient for concrete
 * implementations to extend.
 *
 * @see InterestPrincipalPenaltyFeesOrderLoanRepaymentScheduleTransactionProcessor
 *
 * @see HeavensFamilyLoanRepaymentScheduleTransactionProcessor
 * @see CreocoreLoanRepaymentScheduleTransactionProcessor
 */
@RequiredArgsConstructor
public abstract class AbstractLoanRepaymentScheduleTransactionProcessor implements LoanRepaymentScheduleTransactionProcessor {

    protected final SingleLoanChargeRepaymentScheduleProcessingWrapper loanChargeProcessor = new SingleLoanChargeRepaymentScheduleProcessingWrapper();
    protected final ExternalIdFactory externalIdFactory;
    protected final LoanChargeValidator loanChargeValidator;
    protected final LoanBalanceService loanBalanceService;

    @Override
    public boolean accept(String s) {
        return getCode().equalsIgnoreCase(s) || getName().equalsIgnoreCase(s);
    }

    @Override
    public ChangedTransactionDetail reprocessLoanTransactions(final LocalDate disbursementDate,
            final List<LoanTransaction> transactionsPostDisbursement, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments, final Set<LoanCharge> charges) {

        if (charges != null) {
            for (final LoanCharge loanCharge : charges) {
                if (!loanCharge.isDueAtDisbursement()) {
                    loanCharge.resetPaidAmount(currency);
                }
            }
        }
        addChargeOnlyRepaymentInstallmentIfRequired(charges, installments);

        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
            currentInstallment.resetDerivedComponents();
            currentInstallment.updateObligationsMet(currency, disbursementDate);
        }

        // re-process loan charges over repayment periods (picking up on waived
        // loan charges)
        final LoanRepaymentScheduleProcessingWrapper wrapper = new LoanRepaymentScheduleProcessingWrapper();
        wrapper.reprocess(currency, disbursementDate, installments, charges);

        final ChangedTransactionDetail changedTransactionDetail = new ChangedTransactionDetail();
        final List<LoanTransaction> transactionsToBeProcessed = new ArrayList<>();
        for (final LoanTransaction loanTransaction : transactionsPostDisbursement) {
            if (loanTransaction.isChargePayment()) {
                List<LoanChargePaidDetail> chargePaidDetails = new ArrayList<>();
                final Set<LoanChargePaidBy> chargePaidBies = loanTransaction.getLoanChargesPaid();
                final Set<LoanCharge> transferCharges = new HashSet<>();
                for (final LoanChargePaidBy chargePaidBy : chargePaidBies) {
                    LoanCharge loanCharge = chargePaidBy.getLoanCharge();
                    transferCharges.add(loanCharge);
                    if (loanCharge.isInstalmentFee()) {
                        chargePaidDetails.addAll(loanCharge.fetchRepaymentInstallment(currency));
                    }
                }
                LocalDate startDate = disbursementDate;
                int firstNormalInstallmentNumber = LoanRepaymentScheduleProcessingWrapper.fetchFirstNormalInstallmentNumber(installments);
                for (final LoanRepaymentScheduleInstallment installment : installments) {
                    boolean isFirstPeriod = installment.getInstallmentNumber().equals(firstNormalInstallmentNumber);
                    for (final LoanCharge loanCharge : transferCharges) {
                        boolean isDue = loanCharge.isDueInPeriod(startDate, installment.getDueDate(), isFirstPeriod);
                        if (isDue) {
                            Money amountForProcess = loanCharge.getAmount(currency);
                            if (amountForProcess.isGreaterThan(loanTransaction.getAmount(currency))) {
                                amountForProcess = loanTransaction.getAmount(currency);
                            }
                            LoanChargePaidDetail chargePaidDetail = new LoanChargePaidDetail(amountForProcess, installment,
                                    loanCharge.isFeeCharge());
                            chargePaidDetails.add(chargePaidDetail);
                            break;
                        }
                    }
                    startDate = installment.getDueDate();
                }
                loanTransaction.resetDerivedComponents();
                Money unprocessed = loanTransaction.getAmount(currency);
                for (LoanChargePaidDetail chargePaidDetail : chargePaidDetails) {
                    final List<LoanRepaymentScheduleInstallment> processInstallments = new ArrayList<>(1);
                    processInstallments.add(chargePaidDetail.getInstallment());
                    Money processAmt = chargePaidDetail.getAmount();
                    if (processAmt.isGreaterThan(unprocessed)) {
                        processAmt = unprocessed;
                    }
                    unprocessed = handleTransactionAndCharges(loanTransaction, currency, processInstallments, transferCharges, processAmt,
                            chargePaidDetail.isFeeCharge());
                    if (!unprocessed.isGreaterThanZero()) {
                        break;
                    }
                }

                if (unprocessed.isGreaterThanZero()) {
                    onLoanOverpayment(loanTransaction, unprocessed);
                    loanTransaction.setOverPayments(unprocessed);
                }

            } else {
                transactionsToBeProcessed.add(loanTransaction);
            }
        }

        MoneyHolder overpaymentHolder = new MoneyHolder(Money.zero(currency));
        for (final LoanTransaction loanTransaction : transactionsToBeProcessed) {
            // TODO: analyze and remove this
            if (!loanTransaction.getTypeOf().equals(LoanTransactionType.REFUND_FOR_ACTIVE_LOAN)) {
                final Comparator<LoanRepaymentScheduleInstallment> byDate = Comparator
                        .comparing(LoanRepaymentScheduleInstallment::getDueDate);
                installments.sort(byDate);
            }

            if (loanTransaction.isRepaymentLikeType() || loanTransaction.isInterestWaiver() || loanTransaction.isRecoveryRepayment()) {
                // pass through for new transactions
                if (loanTransaction.getId() == null) {
                    processLatestTransaction(loanTransaction, new TransactionCtx(currency, installments, charges, overpaymentHolder, null));
                    loanTransaction.adjustInterestComponent();
                } else {
                    /**
                     * For existing transactions, check if the re-payment breakup (principal, interest, fees, penalties)
                     * has changed.<br>
                     **/
                    final LoanTransaction newLoanTransaction = LoanTransaction.copyTransactionProperties(loanTransaction);

                    // Reset derived component of new loan transaction and
                    // re-process transaction
                    processLatestTransaction(newLoanTransaction,
                            new TransactionCtx(currency, installments, charges, overpaymentHolder, null));
                    newLoanTransaction.adjustInterestComponent();
                    /**
                     * Check if the transaction amounts have changed. If so, reverse the original transaction and update
                     * changedTransactionDetail accordingly
                     **/
                    if (newLoanTransaction.isReversed()) {
                        loanTransaction.reverse();
                        changedTransactionDetail.addTransactionChange(new TransactionChangeData(loanTransaction, loanTransaction));
                    } else if (LoanTransaction.transactionAmountsMatch(currency, loanTransaction, newLoanTransaction)) {
                        loanTransaction.updateLoanTransactionToRepaymentScheduleMappings(
                                newLoanTransaction.getLoanTransactionToRepaymentScheduleMappings());
                    } else {
                        createNewTransaction(loanTransaction, newLoanTransaction, changedTransactionDetail);
                    }
                }

            } else if (loanTransaction.isWriteOff()) {
                loanTransaction.resetDerivedComponents();
                handleWriteOff(loanTransaction, currency, installments);
            } else if (loanTransaction.isRefundForActiveLoan()) {
                loanTransaction.resetDerivedComponents();
                handleRefund(loanTransaction, currency, installments, charges);
            } else if (loanTransaction.isCreditBalanceRefund()) {
                recalculateCreditTransaction(changedTransactionDetail, loanTransaction, currency, installments, overpaymentHolder);
            } else if (loanTransaction.isChargeback()) {
                recalculateCreditTransaction(changedTransactionDetail, loanTransaction, currency, installments, overpaymentHolder);
                reprocessChargebackTransactionRelation(changedTransactionDetail, transactionsToBeProcessed);
            } else if (loanTransaction.isChargeOff()) {
                recalculateChargeOffTransaction(changedTransactionDetail, loanTransaction, currency, installments);
            } else if (loanTransaction.isAccrualActivity()) {
                recalculateAccrualActivityTransaction(changedTransactionDetail, loanTransaction, currency, installments);
            }
        }
        reprocessInstallments(disbursementDate, transactionsToBeProcessed, installments, currency);
        return changedTransactionDetail;
    }

    protected void calculateAccrualActivity(LoanTransaction loanTransaction, MonetaryCurrency currency,
            List<LoanRepaymentScheduleInstallment> installments) {

        final int firstNormalInstallmentNumber = LoanRepaymentScheduleProcessingWrapper.fetchFirstNormalInstallmentNumber(installments);

        final LoanRepaymentScheduleInstallment currentInstallment = installments.stream()
                .filter(installment -> LoanRepaymentScheduleProcessingWrapper.isInPeriod(loanTransaction.getTransactionDate(), installment,
                        installment.getInstallmentNumber().equals(firstNormalInstallmentNumber)))
                .findFirst().orElseThrow();

        if (currentInstallment.isNotFullyPaidOff() && (currentInstallment.getDueDate().isAfter(loanTransaction.getTransactionDate())
                || (currentInstallment.getDueDate().isEqual(loanTransaction.getTransactionDate())
                        && loanTransaction.getTransactionDate().equals(DateUtils.getBusinessLocalDate())))) {
            loanTransaction.reverse();
        } else {
            loanTransaction.resetDerivedComponents();
            final Money principalPortion = Money.zero(currency);
            Money interestPortion = currentInstallment.getInterestCharged(currency);
            Money feeChargesPortion = currentInstallment.getFeeChargesCharged(currency);
            Money penaltyChargesPortion = currentInstallment.getPenaltyChargesCharged(currency);
            if (interestPortion.plus(feeChargesPortion).plus(penaltyChargesPortion).isZero()) {
                loanTransaction.reverse();
            } else {
                loanTransaction.updateComponentsAndTotal(principalPortion, interestPortion, feeChargesPortion, penaltyChargesPortion);
                final Loan loan = loanTransaction.getLoan();
                if ((loan.isClosedObligationsMet() || loanBalanceService.isOverPaid(loan)) && currentInstallment.isObligationsMet()
                        && currentInstallment.isTransactionDateWithinPeriod(currentInstallment.getObligationsMetOnDate())) {
                    loanTransaction.updateTransactionDate(currentInstallment.getObligationsMetOnDate());
                }
            }
        }
    }

    private void recalculateAccrualActivityTransaction(ChangedTransactionDetail changedTransactionDetail, LoanTransaction loanTransaction,
            MonetaryCurrency currency, List<LoanRepaymentScheduleInstallment> installments) {
        final LoanTransaction newLoanTransaction = LoanTransaction.copyTransactionProperties(loanTransaction);

        calculateAccrualActivity(newLoanTransaction, currency, installments);

        if (!LoanTransaction.transactionAmountsMatch(currency, loanTransaction, newLoanTransaction)) {
            createNewTransaction(loanTransaction, newLoanTransaction, changedTransactionDetail);
        }
    }

    @Override
    public ChangedTransactionDetail processLatestTransaction(final LoanTransaction loanTransaction, final TransactionCtx ctx) {
        switch (loanTransaction.getTypeOf()) {
            case WRITEOFF -> handleWriteOff(loanTransaction, ctx.getCurrency(), ctx.getInstallments());
            case REFUND_FOR_ACTIVE_LOAN -> handleRefund(loanTransaction, ctx.getCurrency(), ctx.getInstallments(), ctx.getCharges());
            case CHARGEBACK -> handleChargeback(loanTransaction, ctx);
            case CHARGE_OFF -> handleChargeOff(loanTransaction, ctx);
            default -> {
                Money transactionAmountUnprocessed = handleTransactionAndCharges(loanTransaction, ctx.getCurrency(), ctx.getInstallments(),
                        ctx.getCharges(), null, false);
                if (transactionAmountUnprocessed.isGreaterThanZero()) {
                    if (loanTransaction.isWaiver()) {
                        loanTransaction.updateComponentsAndTotal(transactionAmountUnprocessed.zero(), transactionAmountUnprocessed.zero(),
                                transactionAmountUnprocessed.zero(), transactionAmountUnprocessed.zero());
                    } else {
                        onLoanOverpayment(loanTransaction, transactionAmountUnprocessed);
                        loanTransaction.setOverPayments(transactionAmountUnprocessed);
                    }
                    ctx.getOverpaymentHolder()
                            .setMoneyObject(ctx.getOverpaymentHolder().getMoneyObject().add(transactionAmountUnprocessed));
                } else {
                    ctx.getOverpaymentHolder().setMoneyObject(Money.zero(ctx.getCurrency()));
                }
            }
        }
        return ctx.getChangedTransactionDetail();
    }

    @Override
    public Money handleRepaymentSchedule(final List<LoanTransaction> transactionsPostDisbursement, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments, Set<LoanCharge> loanCharges) {
        Money unProcessed = Money.zero(currency);
        for (final LoanTransaction loanTransaction : transactionsPostDisbursement) {
            if (loanTransaction.isRepaymentLikeType() || loanTransaction.isInterestWaiver() || loanTransaction.isRecoveryRepayment()) {
                loanTransaction.resetDerivedComponents();
            }
            if (loanTransaction.isInterestWaiver()) {
                processTransaction(loanTransaction, currency, installments, loanCharges, null);
            } else {
                unProcessed = processTransaction(loanTransaction, currency, installments, loanCharges, null);
            }
        }
        return unProcessed;
    }

    @Override
    public boolean isInterestFirstRepaymentScheduleTransactionProcessor() {
        return false;
    }

    // abstract interface

    /**
     * For early/'in advance' repayments.
     *
     * @param transactionMappings
     *            TODO
     * @param charges
     */
    protected abstract Money handleTransactionThatIsPaymentInAdvanceOfInstallment(LoanRepaymentScheduleInstallment currentInstallment,
            List<LoanRepaymentScheduleInstallment> installments, LoanTransaction loanTransaction, Money paymentInAdvance,
            List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, Set<LoanCharge> charges);

    /**
     * For normal on-time repayments.
     *
     * @param transactionMappings
     *            TODO
     * @param charges
     */
    protected abstract Money handleTransactionThatIsOnTimePaymentOfInstallment(LoanRepaymentScheduleInstallment currentInstallment,
            LoanTransaction loanTransaction, Money transactionAmountUnprocessed,
            List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, Set<LoanCharge> charges);

    /**
     * For late repayments, how should components of installment be paid off
     *
     * @param transactionMappings
     *            TODO
     * @param charges
     */
    protected abstract Money handleTransactionThatIsALateRepaymentOfInstallment(LoanRepaymentScheduleInstallment currentInstallment,
            List<LoanRepaymentScheduleInstallment> installments, LoanTransaction loanTransaction, Money transactionAmountUnprocessed,
            List<LoanTransactionToRepaymentScheduleMapping> transactionMappings, Set<LoanCharge> charges);

    /**
     * Invoked when a transaction results in an over-payment of the full loan.
     *
     * transaction amount is greater than the total expected principal and interest of the loan.
     */
    @SuppressWarnings("unused")
    protected void onLoanOverpayment(final LoanTransaction loanTransaction, final Money loanOverPaymentAmount) {
        // empty implementation by default.
    }

    /**
     * Invoked when a there is a refund of an active loan or undo of an active loan
     *
     * Undoes principal, interest, fees and charges of this transaction based on the repayment strategy
     *
     * @param transactionMappings
     *            TODO
     *
     */
    protected abstract Money handleRefundTransactionPaymentOfInstallment(LoanRepaymentScheduleInstallment currentInstallment,
            LoanTransaction loanTransaction, Money transactionAmountUnprocessed,
            List<LoanTransactionToRepaymentScheduleMapping> transactionMappings);

    /**
     * This method is responsible for checking if the current transaction is 'an advance/early payment' based on the
     * details passed through.
     *
     * Default implementation is check transaction date is before installment due date.
     */
    protected boolean isTransactionInAdvanceOfInstallment(final int installmentIndex,
            final List<LoanRepaymentScheduleInstallment> installments, final LocalDate transactionDate) {
        final LoanRepaymentScheduleInstallment currentInstallment = installments.get(installmentIndex);
        return DateUtils.isBefore(transactionDate, currentInstallment.getDueDate());
    }

    /**
     * This method is responsible for checking if the current transaction is 'an advance/early payment' based on the
     * details passed through.
     *
     * Default implementation simply processes transactions as 'Late' if the transaction date is after the installment
     * due date.
     */
    protected boolean isTransactionALateRepaymentOnInstallment(final int installmentIndex,
            final List<LoanRepaymentScheduleInstallment> installments, final LocalDate transactionDate) {
        final LoanRepaymentScheduleInstallment currentInstallment = installments.get(installmentIndex);
        return DateUtils.isAfter(transactionDate, currentInstallment.getDueDate());
    }

    private void recalculateChargeOffTransaction(ChangedTransactionDetail changedTransactionDetail, LoanTransaction loanTransaction,
            MonetaryCurrency currency, List<LoanRepaymentScheduleInstallment> installments) {
        final LoanTransaction newLoanTransaction = LoanTransaction.copyTransactionProperties(loanTransaction);

        final BigDecimal newInterest = getInterestTillChargeOffForPeriod(newLoanTransaction.getLoan(),
                newLoanTransaction.getTransactionDate());
        createMissingAccrualTransactionDuringChargeOffIfNeeded(newInterest, newLoanTransaction, newLoanTransaction.getTransactionDate(),
                changedTransactionDetail);

        newLoanTransaction.resetDerivedComponents();
        // determine how much is outstanding total and breakdown for principal, interest and charges
        Money principalPortion = Money.zero(currency);
        Money interestPortion = Money.zero(currency);
        Money feeChargesPortion = Money.zero(currency);
        Money penaltychargesPortion = Money.zero(currency);
        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
            if (currentInstallment.isNotFullyPaidOff()) {
                principalPortion = principalPortion.plus(currentInstallment.getPrincipalOutstanding(currency));
                interestPortion = interestPortion.plus(currentInstallment.getInterestOutstanding(currency));
                feeChargesPortion = feeChargesPortion.plus(currentInstallment.getFeeChargesOutstanding(currency));
                penaltychargesPortion = penaltychargesPortion.plus(currentInstallment.getPenaltyChargesOutstanding(currency));
            }
        }

        newLoanTransaction.updateComponentsAndTotal(principalPortion, interestPortion, feeChargesPortion, penaltychargesPortion);
        if (!LoanTransaction.transactionAmountsMatch(currency, loanTransaction, newLoanTransaction)) {
            createNewTransaction(loanTransaction, newLoanTransaction, changedTransactionDetail);
        }
    }

    private void reprocessChargebackTransactionRelation(ChangedTransactionDetail changedTransactionDetail,
            List<LoanTransaction> transactionsToBeProcessed) {
        List<LoanTransaction> mergedTransactionList = getMergedTransactionList(transactionsToBeProcessed, changedTransactionDetail);
        for (TransactionChangeData change : changedTransactionDetail.getTransactionChanges()) {
            LoanTransaction newTransaction = change.getNewTransaction();
            LoanTransaction oldTransaction = change.getOldTransaction();

            if (newTransaction.isChargeback()) {
                for (LoanTransaction loanTransaction : mergedTransactionList) {
                    if (loanTransaction.isReversed()) {
                        continue;
                    }
                    LoanTransactionRelation newLoanTransactionRelation = null;
                    LoanTransactionRelation oldLoanTransactionRelation = null;
                    for (LoanTransactionRelation transactionRelation : loanTransaction.getLoanTransactionRelations()) {
                        if (LoanTransactionRelationTypeEnum.CHARGEBACK.equals(transactionRelation.getRelationType())
                                && oldTransaction != null && oldTransaction.getId() != null
                                && oldTransaction.getId().equals(transactionRelation.getToTransaction().getId())) {
                            newLoanTransactionRelation = LoanTransactionRelation.linkToTransaction(loanTransaction, newTransaction,
                                    LoanTransactionRelationTypeEnum.CHARGEBACK);
                            oldLoanTransactionRelation = transactionRelation;
                            break;
                        }
                    }
                    if (newLoanTransactionRelation != null) {
                        loanTransaction.getLoanTransactionRelations().add(newLoanTransactionRelation);
                        loanTransaction.getLoanTransactionRelations().remove(oldLoanTransactionRelation);
                    }
                }
            }
        }
    }

    protected void reprocessInstallments(LocalDate disbursementDate, List<LoanTransaction> transactions,
            List<LoanRepaymentScheduleInstallment> installments, MonetaryCurrency currency) {
        LoanRepaymentScheduleInstallment lastInstallment = installments.getLast();
        if (lastInstallment.isAdditional() && lastInstallment.getDue(currency).isZero()) {
            installments.remove(lastInstallment);
        }

        if (isNotObligationsMet(lastInstallment) || isObligationsMetOnDisbursementDate(disbursementDate, lastInstallment)) {
            Optional<LoanTransaction> optWaiverTx = transactions.stream().filter(lt -> {
                LocalDate fromDate = lastInstallment.getFromDate();
                return lt.getTransactionDate().isAfter(fromDate);
            }).filter(LoanTransaction::isChargesWaiver).max(Comparator.comparing(LoanTransaction::getTransactionDate));
            if (optWaiverTx.isPresent()) {
                LoanTransaction waiverTx = optWaiverTx.get();
                LocalDate waiverTxDate = waiverTx.getTransactionDate();
                if (isNotObligationsMet(lastInstallment) || isTransactionAfterObligationsMetOnDate(waiverTxDate, lastInstallment)) {
                    lastInstallment.updateObligationMet(true);
                    lastInstallment.updateObligationMetOnDate(waiverTxDate);
                }
            }
        }

        // TODO: rewrite and handle it at the proper place when disbursement handling got fixed
        for (LoanRepaymentScheduleInstallment loanRepaymentScheduleInstallment : installments) {
            if (loanRepaymentScheduleInstallment.getTotalOutstanding(currency).isGreaterThanZero()) {
                loanRepaymentScheduleInstallment.updateObligationMet(false);
                loanRepaymentScheduleInstallment.updateObligationMetOnDate(null);
            }
        }
    }

    private boolean isTransactionAfterObligationsMetOnDate(LocalDate waiverTxDate, LoanRepaymentScheduleInstallment lastInstallment) {
        return lastInstallment.getObligationsMetOnDate() != null && lastInstallment.getObligationsMetOnDate().isBefore(waiverTxDate);
    }

    private boolean isObligationsMetOnDisbursementDate(LocalDate disbursementDate,
            LoanRepaymentScheduleInstallment loanRepaymentScheduleInstallment) {
        return loanRepaymentScheduleInstallment.isObligationsMet()
                && disbursementDate.equals(loanRepaymentScheduleInstallment.getObligationsMetOnDate());
    }

    protected boolean isNotObligationsMet(LoanRepaymentScheduleInstallment loanRepaymentScheduleInstallment) {
        return !loanRepaymentScheduleInstallment.isObligationsMet() && loanRepaymentScheduleInstallment.getObligationsMetOnDate() == null;
    }

    private void recalculateCreditTransaction(ChangedTransactionDetail changedTransactionDetail, LoanTransaction loanTransaction,
            MonetaryCurrency currency, List<LoanRepaymentScheduleInstallment> installments, MoneyHolder overpaymentHolder) {
        // pass through for new transactions
        if (loanTransaction.getId() == null) {
            return;
        }
        final LoanTransaction newLoanTransaction = LoanTransaction.copyTransactionProperties(loanTransaction);

        processCreditTransaction(newLoanTransaction, overpaymentHolder, currency, installments);
        if (!LoanTransaction.transactionAmountsMatch(currency, loanTransaction, newLoanTransaction)) {
            createNewTransaction(loanTransaction, newLoanTransaction, changedTransactionDetail);
        }
    }

    private List<LoanTransaction> getMergedTransactionList(List<LoanTransaction> transactionList,
            ChangedTransactionDetail changedTransactionDetail) {
        List<LoanTransaction> mergedList = new ArrayList<>(
                changedTransactionDetail.getTransactionChanges().stream().map(TransactionChangeData::getNewTransaction).toList());
        mergedList.addAll(transactionList);
        return mergedList;
    }

    protected void createNewTransaction(LoanTransaction loanTransaction, LoanTransaction newLoanTransaction,
            ChangedTransactionDetail changedTransactionDetail) {
        loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(loanTransaction.getLoan(), loanTransaction, "reversed");
        loanTransaction.reverse();
        loanTransaction.updateExternalId(null);
        newLoanTransaction.copyLoanTransactionRelations(loanTransaction.getLoanTransactionRelations());
        // Adding Replayed relation from newly created transaction to reversed transaction
        newLoanTransaction.getLoanTransactionRelations().add(
                LoanTransactionRelation.linkToTransaction(newLoanTransaction, loanTransaction, LoanTransactionRelationTypeEnum.REPLAYED));
        changedTransactionDetail.addTransactionChange(new TransactionChangeData(loanTransaction, newLoanTransaction));
    }

    protected void processCreditTransaction(LoanTransaction loanTransaction, MoneyHolder overpaymentHolder, MonetaryCurrency currency,
            List<LoanRepaymentScheduleInstallment> installments) {
        loanTransaction.resetDerivedComponents();
        List<LoanTransactionToRepaymentScheduleMapping> transactionMappings = new ArrayList<>();
        final Comparator<LoanRepaymentScheduleInstallment> byDate = Comparator.comparing(LoanRepaymentScheduleInstallment::getDueDate);
        List<LoanRepaymentScheduleInstallment> installmentToBeProcessed = installments.stream().filter(i -> !i.isDownPayment())
                .sorted(byDate).toList();
        final Money zeroMoney = Money.zero(currency);
        Money transactionAmount = loanTransaction.getAmount(currency);
        Money principalPortion = MathUtil.negativeToZero(loanTransaction.getAmount(currency).minus(overpaymentHolder.getMoneyObject()));
        Money repaidAmount = MathUtil.negativeToZero(transactionAmount.minus(principalPortion));
        loanTransaction.setOverPayments(repaidAmount);
        overpaymentHolder.setMoneyObject(overpaymentHolder.getMoneyObject().minus(repaidAmount));
        loanTransaction.updateComponents(principalPortion, zeroMoney, zeroMoney, zeroMoney);

        if (principalPortion.isGreaterThanZero()) {
            final LocalDate transactionDate = loanTransaction.getTransactionDate();
            boolean loanTransactionMapped = false;
            LocalDate pastDueDate = null;
            for (final LoanRepaymentScheduleInstallment currentInstallment : installmentToBeProcessed) {
                pastDueDate = currentInstallment.getDueDate();
                if (!currentInstallment.isAdditional() && DateUtils.isAfter(currentInstallment.getDueDate(), transactionDate)) {
                    currentInstallment.addToCreditedPrincipal(transactionAmount.getAmount());
                    currentInstallment.addToPrincipal(transactionDate, transactionAmount);
                    if (repaidAmount.isGreaterThanZero()) {
                        currentInstallment.payPrincipalComponent(loanTransaction.getTransactionDate(), repaidAmount);
                        transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                                repaidAmount, zeroMoney, zeroMoney, zeroMoney));
                    }
                    loanTransactionMapped = true;
                    break;

                    // If already exists an additional installment just update the due date and
                    // principal from the Loan chargeback / CBR transaction
                } else if (currentInstallment.isAdditional()) {
                    if (DateUtils.isAfter(transactionDate, currentInstallment.getDueDate())) {
                        currentInstallment.updateDueDate(transactionDate);
                    }

                    currentInstallment.updateCredits(transactionDate, transactionAmount);
                    if (repaidAmount.isGreaterThanZero()) {
                        currentInstallment.payPrincipalComponent(loanTransaction.getTransactionDate(), repaidAmount);
                        transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                                repaidAmount, zeroMoney, zeroMoney, zeroMoney));
                    }
                    loanTransactionMapped = true;
                    break;
                }
            }

            // New installment will be added (N+1 scenario)
            if (!loanTransactionMapped) {
                if (loanTransaction.getTransactionDate().equals(pastDueDate)) {
                    LoanRepaymentScheduleInstallment currentInstallment = installmentToBeProcessed.getLast();
                    currentInstallment.addToCreditedPrincipal(transactionAmount.getAmount());
                    currentInstallment.addToPrincipal(transactionDate, transactionAmount);
                    if (repaidAmount.isGreaterThanZero()) {
                        currentInstallment.payPrincipalComponent(loanTransaction.getTransactionDate(), repaidAmount);
                        transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, currentInstallment,
                                repaidAmount, zeroMoney, zeroMoney, zeroMoney));
                    }
                } else {
                    Loan loan = loanTransaction.getLoan();
                    LoanRepaymentScheduleInstallment installment = new LoanRepaymentScheduleInstallment(loan, (installments.size() + 1),
                            pastDueDate, transactionDate, transactionAmount.getAmount(), zeroMoney.getAmount(), zeroMoney.getAmount(),
                            zeroMoney.getAmount(), false, null);
                    installment.markAsAdditional();
                    installment.addToCreditedPrincipal(transactionAmount.getAmount());
                    loan.addLoanRepaymentScheduleInstallment(installment);

                    if (repaidAmount.isGreaterThanZero()) {
                        installment.payPrincipalComponent(loanTransaction.getTransactionDate(), repaidAmount);
                        transactionMappings.add(LoanTransactionToRepaymentScheduleMapping.createFrom(loanTransaction, installment,
                                repaidAmount, zeroMoney, zeroMoney, zeroMoney));
                    }
                }
            }

            loanTransaction.updateLoanTransactionToRepaymentScheduleMappings(transactionMappings);
        }
    }

    protected Money handleTransactionAndCharges(final LoanTransaction loanTransaction, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments, final Set<LoanCharge> charges, final Money chargeAmountToProcess,
            final boolean isFeeCharge) {
        if (loanTransaction.isRepaymentLikeType() || loanTransaction.isInterestWaiver() || loanTransaction.isRecoveryRepayment()) {
            loanTransaction.resetDerivedComponents();
        }
        Money transactionAmountUnprocessed = processTransaction(loanTransaction, currency, installments, charges, chargeAmountToProcess);

        final Set<LoanCharge> loanFees = extractFeeCharges(charges);
        final Set<LoanCharge> loanPenalties = extractPenaltyCharges(charges);
        Integer installmentNumber = null;
        if (loanTransaction.isChargePayment() && installments.size() == 1) {
            installmentNumber = installments.getFirst().getInstallmentNumber();
        }

        if (loanTransaction.isNotWaiver() && !loanTransaction.isAccrual() && !loanTransaction.isAccrualActivity()) {
            Money feeCharges = loanTransaction.getFeeChargesPortion(currency);
            Money penaltyCharges = loanTransaction.getPenaltyChargesPortion(currency);
            if (chargeAmountToProcess != null && feeCharges.isGreaterThan(chargeAmountToProcess)) {
                if (isFeeCharge) {
                    feeCharges = chargeAmountToProcess;
                } else {
                    penaltyCharges = chargeAmountToProcess;
                }
            }
            if (feeCharges.isGreaterThanZero()) {
                updateChargesPaidAmountBy(loanTransaction, feeCharges, loanFees, installmentNumber);
            }

            if (penaltyCharges.isGreaterThanZero()) {
                updateChargesPaidAmountBy(loanTransaction, penaltyCharges, loanPenalties, installmentNumber);
            }
        }
        return transactionAmountUnprocessed;
    }

    protected Money processTransaction(final LoanTransaction loanTransaction, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments, final Set<LoanCharge> charges, Money amountToProcess) {
        int installmentIndex = 0;

        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        Money transactionAmountUnprocessed = loanTransaction.getAmount(currency);
        if (amountToProcess != null) {
            transactionAmountUnprocessed = amountToProcess;
        }
        List<LoanTransactionToRepaymentScheduleMapping> transactionMappings = new ArrayList<>();

        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
            if (transactionAmountUnprocessed.isGreaterThanZero()) {
                if (currentInstallment.isNotFullyPaidOff()) {
                    if (isTransactionInAdvanceOfInstallment(installmentIndex, installments, transactionDate)) {
                        transactionAmountUnprocessed = handleTransactionThatIsPaymentInAdvanceOfInstallment(currentInstallment,
                                installments, loanTransaction, transactionAmountUnprocessed, transactionMappings, charges);
                    } else if (isTransactionALateRepaymentOnInstallment(installmentIndex, installments, transactionDate)) {
                        transactionAmountUnprocessed = handleTransactionThatIsALateRepaymentOfInstallment(currentInstallment, installments,
                                loanTransaction, transactionAmountUnprocessed, transactionMappings, charges);
                    } else {
                        transactionAmountUnprocessed = handleTransactionThatIsOnTimePaymentOfInstallment(currentInstallment,
                                loanTransaction, transactionAmountUnprocessed, transactionMappings, charges);
                    }
                }
            }

            installmentIndex++;
        }
        loanTransaction.updateLoanTransactionToRepaymentScheduleMappings(transactionMappings);
        return transactionAmountUnprocessed;
    }

    protected Set<LoanCharge> extractFeeCharges(final Set<LoanCharge> loanCharges) {
        final Set<LoanCharge> feeCharges = new HashSet<>();
        for (final LoanCharge loanCharge : loanCharges) {
            if (loanCharge.isFeeCharge()) {
                feeCharges.add(loanCharge);
            }
        }
        return feeCharges;
    }

    protected Set<LoanCharge> extractPenaltyCharges(final Set<LoanCharge> loanCharges) {
        final Set<LoanCharge> penaltyCharges = new HashSet<>();
        for (final LoanCharge loanCharge : loanCharges) {
            if (loanCharge.isPenaltyCharge()) {
                penaltyCharges.add(loanCharge);
            }
        }
        return penaltyCharges;
    }

    protected void updateChargesPaidAmountBy(final LoanTransaction loanTransaction, final Money chargeAmount, final Set<LoanCharge> charges,
            final Integer installmentNumber) {

        Money amountRemaining = chargeAmount;
        while (amountRemaining.isGreaterThanZero()) {
            final LoanCharge unpaidCharge = findEarliestUnpaidChargeFromUnOrderedSet(charges, chargeAmount.getCurrency());
            Money feeAmount = chargeAmount.zero();
            if (loanTransaction.isChargePayment()) {
                feeAmount = chargeAmount;
            }
            if (unpaidCharge == null) {
                break; // All are trache charges
            }
            final Money amountPaidTowardsCharge = unpaidCharge.updatePaidAmountBy(amountRemaining, installmentNumber, feeAmount);
            if (!amountPaidTowardsCharge.isZero()) {
                Set<LoanChargePaidBy> chargesPaidBies = loanTransaction.getLoanChargesPaid();
                if (loanTransaction.isChargePayment()) {
                    for (final LoanChargePaidBy chargePaidBy : chargesPaidBies) {
                        LoanCharge loanCharge = chargePaidBy.getLoanCharge();
                        if (loanCharge != null && Objects.equals(loanCharge.getId(), unpaidCharge.getId())) {
                            chargePaidBy.setAmount(amountPaidTowardsCharge.getAmount());
                        }
                    }
                } else {
                    final LoanChargePaidBy loanChargePaidBy = new LoanChargePaidBy(loanTransaction, unpaidCharge,
                            amountPaidTowardsCharge.getAmount(), installmentNumber);
                    chargesPaidBies.add(loanChargePaidBy);
                }
                amountRemaining = amountRemaining.minus(amountPaidTowardsCharge);
            }
        }

    }

    public interface ChargesPaidByFunction {

        void accept(LoanTransaction loanTransaction, Money feeCharges, Set<LoanCharge> charges, Integer installmentNumber);
    }

    public ChargesPaidByFunction getChargesPaymentFunction(LoanRepaymentScheduleInstallment.PaymentAction action) {
        return switch (action) {
            case PAY -> this::updateChargesPaidAmountBy;
            case UNPAY -> this::undoChargesPaidAmountBy;
        };
    }

    protected LoanCharge findEarliestUnpaidChargeFromUnOrderedSet(final Set<LoanCharge> charges, final MonetaryCurrency currency) {
        LoanCharge earliestUnpaidCharge = null;
        LoanCharge installemntCharge = null;
        LoanInstallmentCharge chargePerInstallment = null;
        for (final LoanCharge loanCharge : charges) {
            if (loanCharge.getAmountOutstanding(currency).isGreaterThanZero() && !loanCharge.isDueAtDisbursement()) {
                if (loanCharge.isInstalmentFee()) {
                    LoanInstallmentCharge unpaidLoanChargePerInstallment = loanCharge.getUnpaidInstallmentLoanCharge();
                    if (chargePerInstallment == null || DateUtils.isAfter(chargePerInstallment.getRepaymentInstallment().getDueDate(),
                            unpaidLoanChargePerInstallment.getRepaymentInstallment().getDueDate())) {
                        installemntCharge = loanCharge;
                        chargePerInstallment = unpaidLoanChargePerInstallment;
                    }
                } else if (earliestUnpaidCharge == null
                        || DateUtils.isBefore(loanCharge.getDueLocalDate(), earliestUnpaidCharge.getDueLocalDate())) {
                    earliestUnpaidCharge = loanCharge;
                }
            }
        }
        if (earliestUnpaidCharge == null || (chargePerInstallment != null && DateUtils.isAfter(earliestUnpaidCharge.getDueLocalDate(),
                chargePerInstallment.getRepaymentInstallment().getDueDate()))) {
            earliestUnpaidCharge = installemntCharge;
        }

        return earliestUnpaidCharge;
    }

    protected void handleWriteOff(final LoanTransaction loanTransaction, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments) {

        final LocalDate transactionDate = loanTransaction.getTransactionDate();
        Money principalPortion = Money.zero(currency);
        Money interestPortion = Money.zero(currency);
        Money feeChargesPortion = Money.zero(currency);
        Money penaltychargesPortion = Money.zero(currency);

        // determine how much is written off in total and breakdown for
        // principal, interest and charges
        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {

            if (currentInstallment.isNotFullyPaidOff()) {
                principalPortion = principalPortion.plus(currentInstallment.writeOffOutstandingPrincipal(transactionDate, currency));
                interestPortion = interestPortion.plus(currentInstallment.writeOffOutstandingInterest(transactionDate, currency));
                feeChargesPortion = feeChargesPortion.plus(currentInstallment.writeOffOutstandingFeeCharges(transactionDate, currency));
                penaltychargesPortion = penaltychargesPortion
                        .plus(currentInstallment.writeOffOutstandingPenaltyCharges(transactionDate, currency));
            }
        }

        loanTransaction.updateComponentsAndTotal(principalPortion, interestPortion, feeChargesPortion, penaltychargesPortion);
    }

    protected void handleChargeback(LoanTransaction loanTransaction, TransactionCtx ctx) {
        processCreditTransaction(loanTransaction, ctx.getOverpaymentHolder(), ctx.getCurrency(), ctx.getInstallments());
    }

    private void handleChargeOff(LoanTransaction loanTransaction, TransactionCtx transactionCtx) {
        recalculateChargeOffTransaction(transactionCtx.getChangedTransactionDetail(), loanTransaction, transactionCtx.getCurrency(),
                transactionCtx.getInstallments());
    }

    protected void handleCreditBalanceRefund(LoanTransaction loanTransaction, MonetaryCurrency currency,
            List<LoanRepaymentScheduleInstallment> installments, MoneyHolder overpaidAmountHolder) {
        processCreditTransaction(loanTransaction, overpaidAmountHolder, currency, installments);
    }

    protected void handleRefund(LoanTransaction loanTransaction, MonetaryCurrency currency,
            List<LoanRepaymentScheduleInstallment> installments, final Set<LoanCharge> charges) {
        List<LoanTransactionToRepaymentScheduleMapping> transactionMappings = new ArrayList<>();
        final Comparator<LoanRepaymentScheduleInstallment> byDate = Comparator.comparing(LoanRepaymentScheduleInstallment::getDueDate);
        installments.sort(Collections.reverseOrder(byDate));
        Money transactionAmountUnprocessed = loanTransaction.getAmount(currency);

        for (final LoanRepaymentScheduleInstallment currentInstallment : installments) {
            Money outstanding = currentInstallment.getTotalOutstanding(currency);
            Money due = currentInstallment.getDue(currency);

            if (outstanding.isLessThan(due)) {
                transactionAmountUnprocessed = handleRefundTransactionPaymentOfInstallment(currentInstallment, loanTransaction,
                        transactionAmountUnprocessed, transactionMappings);

            }

            if (transactionAmountUnprocessed.isZero()) {
                break;
            }

        }

        final Set<LoanCharge> loanFees = extractFeeCharges(charges);
        final Set<LoanCharge> loanPenalties = extractPenaltyCharges(charges);
        Integer installmentNumber = null;

        final Money feeCharges = loanTransaction.getFeeChargesPortion(currency);
        if (feeCharges.isGreaterThanZero()) {
            undoChargesPaidAmountBy(loanTransaction, feeCharges, loanFees, installmentNumber);
        }

        final Money penaltyCharges = loanTransaction.getPenaltyChargesPortion(currency);
        if (penaltyCharges.isGreaterThanZero()) {
            undoChargesPaidAmountBy(loanTransaction, penaltyCharges, loanPenalties, installmentNumber);
        }
        loanTransaction.updateLoanTransactionToRepaymentScheduleMappings(transactionMappings);
    }

    protected void undoChargesPaidAmountBy(final LoanTransaction loanTransaction, final Money chargeAmount, final Set<LoanCharge> charges,
            final Integer installmentNumber) {

        Money amountRemaining = chargeAmount;
        while (amountRemaining.isGreaterThanZero()) {
            final LoanCharge paidCharge = findLatestPaidChargeFromUnOrderedSet(charges, chargeAmount.getCurrency());

            if (paidCharge != null) {
                Money feeAmount = chargeAmount.zero();

                final Money amountDeductedTowardsCharge = paidCharge.undoPaidOrPartiallyAmountBy(amountRemaining, installmentNumber,
                        feeAmount);
                if (amountDeductedTowardsCharge.isGreaterThanZero()) {

                    final LoanChargePaidBy loanChargePaidBy = new LoanChargePaidBy(loanTransaction, paidCharge,
                            amountDeductedTowardsCharge.getAmount().multiply(new BigDecimal(-1)), null);
                    loanTransaction.getLoanChargesPaid().add(loanChargePaidBy);

                    amountRemaining = amountRemaining.minus(amountDeductedTowardsCharge);
                }
            }
        }

    }

    private LoanCharge findLatestPaidChargeFromUnOrderedSet(final Set<LoanCharge> charges, MonetaryCurrency currency) {
        LoanCharge latestPaidCharge = null;
        LoanCharge installemntCharge = null;
        LoanInstallmentCharge chargePerInstallment = null;
        for (final LoanCharge loanCharge : charges) {
            boolean isPaidOrPartiallyPaid = loanCharge.isPaidOrPartiallyPaid(currency);
            if (isPaidOrPartiallyPaid && !loanCharge.isDueAtDisbursement()) {
                if (loanCharge.isInstalmentFee()) {
                    LoanInstallmentCharge paidLoanChargePerInstallment = loanCharge
                            .getLastPaidOrPartiallyPaidInstallmentLoanCharge(currency);
                    if (chargePerInstallment == null || (paidLoanChargePerInstallment != null
                            && DateUtils.isBefore(chargePerInstallment.getRepaymentInstallment().getDueDate(),
                                    paidLoanChargePerInstallment.getRepaymentInstallment().getDueDate()))) {
                        installemntCharge = loanCharge;
                        chargePerInstallment = paidLoanChargePerInstallment;
                    }
                } else if (latestPaidCharge == null || (loanCharge.isPaidOrPartiallyPaid(currency)
                        && DateUtils.isAfter(loanCharge.getDueLocalDate(), latestPaidCharge.getDueLocalDate()))) {
                    latestPaidCharge = loanCharge;
                }
            }
        }
        if (latestPaidCharge == null || (chargePerInstallment != null
                && DateUtils.isAfter(latestPaidCharge.getDueLocalDate(), chargePerInstallment.getRepaymentInstallment().getDueDate()))) {
            latestPaidCharge = installemntCharge;
        }

        return latestPaidCharge;
    }

    protected void addChargeOnlyRepaymentInstallmentIfRequired(Set<LoanCharge> charges,
            List<LoanRepaymentScheduleInstallment> installments) {
        LoanCharge latestCharge = getLatestLoanChargeWithSpecificDueDate(charges);
        if (latestCharge == null) {
            return;
        }
        loanChargeProcessor.addChargeOnlyRepaymentInstallmentIfRequired(latestCharge, installments);
    }

    protected LoanCharge getLatestLoanChargeWithSpecificDueDate(Set<LoanCharge> charges) {
        if (charges == null) {
            return null;
        }
        LoanCharge latestCharge = null;
        final List<LoanCharge> chargesWithSpecificDueDate = new ArrayList<>(
                charges.stream().filter(LoanCharge::isSpecifiedDueDate).toList());
        if (!CollectionUtils.isEmpty(chargesWithSpecificDueDate)) {
            chargesWithSpecificDueDate
                    .sort((charge1, charge2) -> DateUtils.compare(charge1.getEffectiveDueDate(), charge2.getEffectiveDueDate()));
            latestCharge = chargesWithSpecificDueDate.getLast();
        }
        return latestCharge;
    }

    private BigDecimal getInterestTillChargeOffForPeriod(final Loan loan, final LocalDate chargeOffDate) {
        BigDecimal interestTillChargeOff = BigDecimal.ZERO;
        final MonetaryCurrency currency = loan.getCurrency();

        final List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments().stream()
                .filter(i -> !i.isAdditional()).toList();

        for (LoanRepaymentScheduleInstallment installment : installments) {
            final boolean isPastPeriod = !installment.getDueDate().isAfter(chargeOffDate);
            final boolean isInPeriod = !installment.getFromDate().isAfter(chargeOffDate) && installment.getDueDate().isAfter(chargeOffDate);

            BigDecimal interest = BigDecimal.ZERO;

            if (isPastPeriod) {
                interest = installment.getInterestCharged(currency).minus(installment.getCreditedInterest()).getAmount();
            } else if (isInPeriod) {
                final BigDecimal totalInterest = installment.getInterestOutstanding(currency).getAmount();
                if (LoanChargeOffBehaviour.ZERO_INTEREST.equals(loan.getLoanProductRelatedDetail().getChargeOffBehaviour())
                        || LoanChargeOffBehaviour.ACCELERATE_MATURITY.equals(loan.getLoanProductRelatedDetail().getChargeOffBehaviour())) {
                    interest = totalInterest;
                } else {
                    final long totalDaysInPeriod = ChronoUnit.DAYS.between(installment.getFromDate(), installment.getDueDate());
                    final long daysTillChargeOff = ChronoUnit.DAYS.between(installment.getFromDate(), chargeOffDate);
                    final MathContext mc = MoneyHelper.getMathContext();

                    interest = Money.of(currency, totalInterest.divide(BigDecimal.valueOf(totalDaysInPeriod), mc)
                            .multiply(BigDecimal.valueOf(daysTillChargeOff), mc), mc).getAmount();
                }
            }
            interestTillChargeOff = interestTillChargeOff.add(interest);
        }

        return interestTillChargeOff;
    }

    private void createMissingAccrualTransactionDuringChargeOffIfNeeded(final BigDecimal newInterest,
            final LoanTransaction chargeOffTransaction, final LocalDate chargeOffDate,
            final ChangedTransactionDetail changedTransactionDetail) {
        final Loan loan = chargeOffTransaction.getLoan();
        final List<LoanRepaymentScheduleInstallment> relevantInstallments = loan.getRepaymentScheduleInstallments().stream()
                .filter(i -> !i.getFromDate().isAfter(chargeOffDate)).toList();

        if (relevantInstallments.isEmpty()) {
            return;
        }

        final BigDecimal sumOfAccrualsTillChargeOff = loan.getLoanTransactions().stream()
                .filter(lt -> lt.isAccrual() && !lt.getTransactionDate().isAfter(chargeOffDate) && lt.isNotReversed())
                .map(lt -> Optional.ofNullable(lt.getInterestPortion()).orElse(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);

        final BigDecimal sumOfAccrualAdjustmentsTillChargeOff = loan.getLoanTransactions().stream()
                .filter(lt -> lt.isAccrualAdjustment() && !lt.getTransactionDate().isAfter(chargeOffDate) && lt.isNotReversed())
                .map(lt -> Optional.ofNullable(lt.getInterestPortion()).orElse(BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);

        final BigDecimal missingAccrualAmount = newInterest.subtract(sumOfAccrualsTillChargeOff).add(sumOfAccrualAdjustmentsTillChargeOff);

        if (missingAccrualAmount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        final LoanTransaction newAccrualTransaction;

        if (missingAccrualAmount.compareTo(BigDecimal.ZERO) > 0) {
            newAccrualTransaction = accrueTransaction(loan, loan.getOffice(), chargeOffDate, missingAccrualAmount, missingAccrualAmount,
                    ZERO, ZERO, externalIdFactory.create());
        } else {
            newAccrualTransaction = accrualAdjustment(loan, loan.getOffice(), chargeOffDate, missingAccrualAmount.abs(),
                    missingAccrualAmount.abs(), ZERO, ZERO, externalIdFactory.create());
        }

        changedTransactionDetail.addNewTransactionChangeBeforeExistingOne(new TransactionChangeData(null, newAccrualTransaction),
                chargeOffTransaction);
    }
}
