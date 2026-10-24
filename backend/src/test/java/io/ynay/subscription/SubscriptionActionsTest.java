package io.ynay.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ynay.action.ActionException;
import io.ynay.month.BudgetFixture;
import io.ynay.report.FlowReportActions;
import io.ynay.report.FlowReportActions.CashFlowInput;
import io.ynay.security.YnayUser;
import io.ynay.support.IntegrationTest;
import io.ynay.subscription.SubscriptionActions.Cadence;
import io.ynay.subscription.SubscriptionActions.CreateSubscriptionInput;
import io.ynay.subscription.SubscriptionActions.DeleteSubscriptionInput;
import io.ynay.subscription.SubscriptionActions.Kind;
import io.ynay.subscription.SubscriptionActions.ListSubscriptionsInput;
import io.ynay.subscription.SubscriptionActions.Status;
import io.ynay.subscription.SubscriptionActions.SubscriptionDto;
import io.ynay.subscription.SubscriptionActions.UpdateSubscriptionInput;
import io.ynay.transaction.TransactionActions;
import io.ynay.transaction.TransactionActions.ListTransactionsInput;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Amounts in milliunits (1_000 = 1.00). */
class SubscriptionActionsTest extends IntegrationTest {

    @Autowired
    SubscriptionActions subscriptions;

    @Autowired
    FlowReportActions reports;

    @Autowired
    TransactionActions transactions;

    BudgetFixture f;
    UUID checking;
    UUID media;
    UUID spotify;
    UUID apple;
    UUID gym;

    static final LocalDate AS_OF = LocalDate.of(2026, 10, 21);

    @BeforeEach
    void setUp() {
        YnayUser user = loginAsNewUser();
        f = BudgetFixture.create(jdbc, user.userId());
        checking = f.account("Checking", "CHECKING", true);
        media = f.category(f.group("Wants"), "Media");
        spotify = f.payee("Spotify");
        apple = f.payee("Apple");
        gym = f.payee("Gym");
        for (int m = 1; m <= 9; m++) {
            f.txn(checking, "2026-0" + m + "-20", m < 6 ? -9_990 : -12_990, media, "Spotify");
            f.txn(checking, "2026-0" + m + "-07", -39_000, media, "Gym");
        }
        f.txn(checking, "2026-07-02", -2_990, media, "Apple");
        f.txn(checking, "2026-08-02", -2_990, media, "Apple");
        f.txn(checking, "2026-08-15", -14_990, media, "Apple");
        f.txn(checking, "2026-09-02", -2_990, media, "Apple");
        f.txn(checking, "2026-10-02", -2_990, media, "Apple");
        f.txn(checking, "2026-10-02", -1_000, media, "Apple"); // same day: one charge of 3.99
    }

    SubscriptionDto create(String name, Cadence cadence, boolean variable, UUID... payees) {
        return subscriptions.createSubscription(new CreateSubscriptionInput(f.budgetId, name, null, cadence, "Media",
                null, variable, List.of(payees), null));
    }

    SubscriptionDto find(String name) {
        return subscriptions.listSubscriptions(new ListSubscriptionsInput(f.budgetId, AS_OF)).stream()
                .filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void derivesChargesCostAndStatus() {
        create("Spotify", Cadence.MONTHLY, false, spotify);
        SubscriptionDto s = find("Spotify");
        assertThat(s.kind()).isEqualTo(Kind.SUBSCRIPTION);
        assertThat(s.payees()).extracting(SubscriptionActions.PayeeRef::name).containsExactly("Spotify");
        assertThat(s.chargeCount()).isEqualTo(9);
        assertThat(s.firstCharge()).isEqualTo(LocalDate.of(2026, 1, 20));
        assertThat(s.lastCharge()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(s.lastAmount()).isEqualTo(12_990);
        assertThat(s.previousAmount()).isEqualTo(12_990);
        assertThat(s.monthlyCost()).isEqualTo(12_990);
        assertThat(s.last12Months()).isEqualTo(5 * 9_990 + 4 * 12_990); // Oct 2025 - Sep 2026
        assertThat(s.nextCharge()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(s.status()).isEqualTo(Status.ACTIVE);
        // a month without a charge after nextCharge makes it overdue
        assertThat(subscriptions.listSubscriptions(new ListSubscriptionsInput(f.budgetId, LocalDate.of(2026, 11, 5)))
                .getFirst().status()).isEqualTo(Status.OVERDUE);

        create("Apple", Cadence.MONTHLY, true, apple);
        SubscriptionDto a = find("Apple");
        assertThat(a.lastAmount()).isEqualTo(3_990);
        assertThat(a.previousAmount()).isEqualTo(2_990);
        assertThat(a.monthlyCost()).isEqualTo(Math.round((2_990 + 14_990 + 2_990 + 2_990) / 3.0)); // Jul - Sep

        SubscriptionDto gymYearly = create("Gym", Cadence.YEARLY, false, gym);
        assertThat(gymYearly.monthlyCost()).isEqualTo(3_250);
    }

    @Test
    void updateCancelAndRelink() {
        SubscriptionDto s = create("Streaming", Cadence.MONTHLY, false, spotify);
        SubscriptionDto other = create("Other", Cadence.MONTHLY, false, apple);
        // moving a payee to another subscription unlinks it from the first
        subscriptions.updateSubscription(new UpdateSubscriptionInput(f.budgetId, other.id(), "Bundle", Kind.FIXED,
                Cadence.QUARTERLY, "", "Price up in May", null, List.of(apple, spotify), null, null));
        assertThat(find("Streaming").payees()).isEmpty();
        SubscriptionDto bundle = find("Bundle");
        assertThat(bundle.kind()).isEqualTo(Kind.FIXED);
        assertThat(bundle.cadence()).isEqualTo(Cadence.QUARTERLY);
        assertThat(bundle.groupName()).isNull();
        assertThat(bundle.note()).isEqualTo("Price up in May");
        assertThat(bundle.payees()).hasSize(2);

        subscriptions.updateSubscription(new UpdateSubscriptionInput(f.budgetId, s.id(), null, null, null, null, null,
                null, List.of(spotify), LocalDate.of(2026, 10, 1), null));
        SubscriptionDto ended = find("Streaming");
        assertThat(ended.status()).isEqualTo(Status.ENDED);
        assertThat(ended.nextCharge()).isNull();
        assertThat(ended.monthlyCost()).isEqualTo(12_990);
        subscriptions.updateSubscription(new UpdateSubscriptionInput(f.budgetId, s.id(), null, null, null, null, null,
                null, null, null, true));
        assertThat(find("Streaming").cancelledOn()).isNull();

        assertThatThrownBy(() -> subscriptions.updateSubscription(new UpdateSubscriptionInput(f.budgetId, s.id(),
                null, null, null, null, null, null, List.of(UUID.randomUUID()), null, null)))
                .isInstanceOf(ActionException.class);
        subscriptions.deleteSubscription(new DeleteSubscriptionInput(f.budgetId, s.id()));
        assertThat(subscriptions.listSubscriptions(new ListSubscriptionsInput(f.budgetId, AS_OF)))
                .extracting(SubscriptionDto::name).containsExactly("Bundle");
    }

    @Test
    void subscriptionChargesShowInCashFlowAndTransactions() {
        create("Spotify", Cadence.MONTHLY, false, spotify);
        subscriptions.createSubscription(new CreateSubscriptionInput(f.budgetId, "Gym", Kind.FIXED, null, null, null,
                null, List.of(gym), null));
        var months = reports.cashFlow(new CashFlowInput(f.budgetId, YearMonth.of(2026, 9), YearMonth.of(2026, 9)))
                .months();
        assertThat(months.getFirst().subscriptions()).isEqualTo(12_990); // fixed contracts don't count
        assertThat(transactions.listTransactions(new ListTransactionsInput(f.budgetId, null, null, null, null, null,
                null, null, true, null, null)).total()).isEqualTo(18);
    }
}
