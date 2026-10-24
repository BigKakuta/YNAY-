package io.ynay.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ynay.action.ActionException;
import io.ynay.month.BudgetFixture;
import io.ynay.month.BudgetFixture.Line;
import io.ynay.report.FlowReportActions.CashFlow;
import io.ynay.report.FlowReportActions.CashFlowInput;
import io.ynay.report.FlowReportActions.CashFlowMonth;
import io.ynay.report.FlowReportActions.CashFlowRow;
import io.ynay.report.FlowReportActions.FlowKind;
import io.ynay.report.FlowReportActions.Payment;
import io.ynay.report.FlowReportActions.PaymentsInput;
import io.ynay.report.PurchaseReviewActions.ReviewPurchaseInput;
import io.ynay.security.YnayUser;
import io.ynay.support.IntegrationTest;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Amounts in milliunits (1_000 = 1.00). */
class FlowReportActionsTest extends IntegrationTest {

    @Autowired
    FlowReportActions reports;

    @Autowired
    PurchaseReviewActions reviews;

    BudgetFixture f;
    UUID checking;
    UUID etf;
    UUID groceries;
    UUID shopping;
    UUID investing;
    UUID loanPayment;
    UUID splitTxn;

    @BeforeEach
    void setUp() {
        YnayUser user = loginAsNewUser();
        f = BudgetFixture.create(jdbc, user.userId());
        checking = f.account("Checking", "CHECKING", true);
        UUID savings = f.account("Savings", "SAVINGS", true);
        etf = f.account("ETF depot", "ASSET", false);
        UUID loan = f.account("Car loan", "LOAN", false);
        UUID needs = f.group("Needs");
        UUID wants = f.group("Wants");
        groceries = f.category(needs, "Groceries");
        loanPayment = f.category(needs, "Loan payment");
        shopping = f.category(wants, "Shopping");
        investing = f.category(wants, "Investing");

        f.startingBalance(checking, "2026-01-01", 2_000_000); // not income
        f.txn(checking, "2026-01-25", 3_000_000, f.rtaId, "Employer");
        f.txn(checking, "2026-01-26", 2_500, f.rtaId, "Bank interest");
        UUID rewe = f.txn(checking, "2026-01-03", -40_000, groceries, "REWE");
        jdbc.sql("update txn set time = '18:42' where id = ?").param(rewe).update();
        f.txn(checking, "2026-01-04", -10_000, groceries, "Bakery");
        f.txn(checking, "2026-01-05", -5_000, null, "Kiosk"); // uncategorized spending
        f.txn(checking, "2026-01-06", 2_000, groceries, "REWE"); // refund
        splitTxn = f.split(checking, "2026-01-10", new Line(-30_000, groceries), new Line(-250_000, shopping));
        jdbc.sql("update txn set payee_id = ? where id = ?").params(f.payee("Amazon"), splitTxn).update();
        jdbc.sql("update txn_split set memo = 'Headphones' where transaction_id = ? and amount = -250000")
                .param(splitTxn).update();
        f.transfer(checking, savings, "2026-01-11", 500_000, null); // between budget accounts: left out
        f.transfer(checking, etf, "2026-01-12", 400_000, investing); // saving, despite the category
        f.transfer(etf, checking, "2026-02-12", 100_000, null); // taking money out of the depot
        f.transfer(checking, loan, "2026-01-13", 200_000, loanPayment); // a loan payment is spending
        f.txn(etf, "2026-01-31", 50_000, null, "Market gains"); // tracking account: ignored
    }

    @Test
    void cashFlowClassifiesIncomeSpendingAndSaving() {
        CashFlow flow = reports.cashFlow(new CashFlowInput(f.budgetId, YearMonth.of(2026, 1), YearMonth.of(2026, 2)));
        assertThat(flow.months()).containsExactly(
                new CashFlowMonth(YearMonth.of(2026, 1), 3_002_500, 533_000, 400_000, 0),
                new CashFlowMonth(YearMonth.of(2026, 2), 0, 0, -100_000, 0));

        CashFlowRow saving = flow.rows().stream()
                .filter(r -> r.kind() == FlowKind.SAVING && r.month().equals(YearMonth.of(2026, 1)))
                .findFirst().orElseThrow();
        assertThat(saving.accountId()).isEqualTo(etf);
        assertThat(saving.accountName()).isEqualTo("ETF depot");
        assertThat(saving.amount()).isEqualTo(400_000);

        CashFlowRow reweRow = flow.rows().stream()
                .filter(r -> "REWE".equals(r.payeeName()) && r.categoryId() != null)
                .findFirst().orElseThrow();
        assertThat(reweRow.amount()).isEqualTo(38_000); // net of the refund
        assertThat(reweRow.count()).isEqualTo(2);
        assertThat(reweRow.groupName()).isEqualTo("Needs");

        assertThat(flow.rows()).anySatisfy(r -> {
            assertThat(r.kind()).isEqualTo(FlowKind.SPENDING);
            assertThat(r.categoryId()).isNull();
            assertThat(r.payeeName()).isEqualTo("Kiosk");
        });
        assertThat(flow.rows()).anySatisfy(r -> {
            assertThat(r.categoryId()).isEqualTo(loanPayment);
            assertThat(r.amount()).isEqualTo(200_000);
        });
        assertThat(flow.rows()).noneSatisfy(r -> assertThat(r.payeeName()).isIn("Starting Balance", "Market gains"));
    }

    @Test
    void paymentsAreSpendingLinesWithFilters() {
        List<Payment> all = reports.payments(new PaymentsInput(f.budgetId, YearMonth.of(2026, 1),
                YearMonth.of(2026, 1), null, null, null, null, null)).payments();
        assertThat(all).extracting(Payment::amount)
                .containsExactlyInAnyOrder(40_000L, 10_000L, 5_000L, -2_000L, 30_000L, 250_000L, 200_000L);

        Payment reweLine = all.stream().filter(p -> p.amount() == 40_000).findFirst().orElseThrow();
        assertThat(reweLine.time()).isEqualTo("18:42");
        assertThat(reweLine.payeeName()).isEqualTo("REWE");
        assertThat(reweLine.groupName()).isEqualTo("Needs");

        Payment headphones = reports.payments(new PaymentsInput(f.budgetId, null, null, null, null, "headphones",
                null, null)).payments().getFirst();
        assertThat(headphones.payeeName()).isEqualTo("Amazon"); // from the split parent
        assertThat(headphones.categoryId()).isEqualTo(shopping);
        assertThat(headphones.splitId()).isNotNull();

        assertThat(reports.payments(new PaymentsInput(f.budgetId, null, null, List.of(groceries), null, null,
                20_000L, null)).payments()).extracting(Payment::amount).containsExactlyInAnyOrder(40_000L, 30_000L);
        assertThat(reports.payments(new PaymentsInput(f.budgetId, null, null, null, List.of(f.payee("Amazon")),
                null, null, null)).payments()).hasSize(2);
        var page = reports.payments(new PaymentsInput(f.budgetId, null, null, null, null, null, null, 3));
        assertThat(page.payments()).hasSize(3);
        assertThat(page.truncated()).isTrue();
    }

    @Test
    void purchaseReviewsAttachToLines() {
        Payment line = reports.payments(new PaymentsInput(f.budgetId, null, null, null, null, "headphones", null,
                null)).payments().getFirst();
        reviews.reviewPurchase(new ReviewPurchaseInput(f.budgetId, line.transactionId(), line.splitId(), 2, null, null));
        reviews.reviewPurchase(new ReviewPurchaseInput(f.budgetId, line.transactionId(), line.splitId(), 4, null, null));
        assertThat(reports.payments(new PaymentsInput(f.budgetId, null, null, null, null, "headphones", null, null))
                .payments().getFirst().review()).isEqualTo(new FlowReportActions.Review(4, null));

        reviews.reviewPurchase(new ReviewPurchaseInput(f.budgetId, line.transactionId(), null, null,
                "Household basics", null));
        assertThat(jdbc.sql("select count(*) from purchase_review where transaction_id = ?")
                .param(line.transactionId()).query(Long.class).single()).isEqualTo(2);

        reviews.reviewPurchase(new ReviewPurchaseInput(f.budgetId, line.transactionId(), line.splitId(), null, null,
                true));
        assertThat(reports.payments(new PaymentsInput(f.budgetId, null, null, null, null, "headphones", null, null))
                .payments().getFirst().review()).isNull();

        assertThatThrownBy(() -> reviews.reviewPurchase(new ReviewPurchaseInput(f.budgetId, line.transactionId(),
                null, 6, null, null))).isInstanceOf(ActionException.class);
        assertThatThrownBy(() -> reviews.reviewPurchase(new ReviewPurchaseInput(f.budgetId, line.transactionId(),
                null, null, " ", null))).isInstanceOf(ActionException.class);
        assertThatThrownBy(() -> reviews.reviewPurchase(new ReviewPurchaseInput(f.budgetId, line.transactionId(),
                UUID.randomUUID(), 3, null, null))).isInstanceOf(ActionException.class);
    }

    @Test
    void validationAndAccess() {
        assertThatThrownBy(() -> reports.cashFlow(new CashFlowInput(f.budgetId, null, YearMonth.of(2026, 1))))
                .isInstanceOf(ActionException.class);
        assertThatThrownBy(() -> reports.payments(new PaymentsInput(f.budgetId, null, null, null, null, null, null,
                0))).isInstanceOf(ActionException.class);
        loginAs(createUser("other-" + UUID.randomUUID()));
        assertThatThrownBy(() -> reports.payments(new PaymentsInput(f.budgetId, null, null, null, null, null, null,
                null)))
                .isInstanceOf(ActionException.class)
                .extracting(e -> ((ActionException) e).code())
                .isEqualTo(ActionException.Code.NOT_FOUND);
        assertThatThrownBy(() -> reviews.reviewPurchase(new ReviewPurchaseInput(f.budgetId, splitTxn, null, 3, null,
                null))).isInstanceOf(ActionException.class);
    }
}
