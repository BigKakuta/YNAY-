package io.ynay.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ynay.action.ActionException;
import io.ynay.device.DeviceActions.CreateDeviceInput;
import io.ynay.device.DeviceActions.DeleteDeviceInput;
import io.ynay.device.DeviceActions.DeviceDto;
import io.ynay.device.DeviceActions.ListDevicesInput;
import io.ynay.device.DeviceActions.UpdateDeviceInput;
import io.ynay.month.BudgetFixture;
import io.ynay.report.ReportSettingsActions;
import io.ynay.report.ReportSettingsActions.GetReportSettingsInput;
import io.ynay.report.ReportSettingsActions.ReportSettings;
import io.ynay.report.ReportSettingsActions.UpdateReportSettingsInput;
import io.ynay.security.YnayUser;
import io.ynay.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Devices for the replacement budget and the per-budget report settings. */
class DeviceAndSettingsTest extends IntegrationTest {

    @Autowired
    DeviceActions devices;

    @Autowired
    ReportSettingsActions settings;

    BudgetFixture f;

    @BeforeEach
    void setUp() {
        YnayUser user = loginAsNewUser();
        f = BudgetFixture.create(jdbc, user.userId());
    }

    @Test
    void devicesCrud() {
        DeviceDto phone = devices.createDevice(new CreateDeviceInput(f.budgetId, "iPhone 17", 1_008_000L,
                LocalDate.of(2025, 12, 5), 4, null, null));
        assertThat(phone.monthlyCost()).isEqualTo(21_000);
        assertThat(phone.replaceOn()).isEqualTo(LocalDate.of(2029, 12, 5));
        assertThat(phone.active()).isTrue();
        devices.createDevice(new CreateDeviceInput(f.budgetId, "TV", 1_728_000L, LocalDate.of(2024, 6, 1), 8, false,
                "Living room"));
        assertThat(devices.listDevices(new ListDevicesInput(f.budgetId))).extracting(DeviceDto::name)
                .containsExactly("TV", "iPhone 17");

        DeviceDto updated = devices.updateDevice(new UpdateDeviceInput(f.budgetId, phone.id(), null, 1_200_000L, null,
                5, false, "Pro"));
        assertThat(updated.monthlyCost()).isEqualTo(20_000);
        assertThat(updated.active()).isFalse();
        assertThat(updated.note()).isEqualTo("Pro");
        assertThat(updated.name()).isEqualTo("iPhone 17");

        assertThatThrownBy(() -> devices.updateDevice(new UpdateDeviceInput(f.budgetId, phone.id(), null, null, null,
                0, null, null))).isInstanceOf(ActionException.class);
        assertThatThrownBy(() -> devices.createDevice(new CreateDeviceInput(f.budgetId, "X", -1L,
                LocalDate.of(2025, 1, 1), 3, null, null))).isInstanceOf(ActionException.class);
        devices.deleteDevice(new DeleteDeviceInput(f.budgetId, phone.id()));
        assertThat(devices.listDevices(new ListDevicesInput(f.budgetId))).hasSize(1);

        loginAs(createUser("other-" + UUID.randomUUID()));
        assertThatThrownBy(() -> devices.listDevices(new ListDevicesInput(f.budgetId)))
                .isInstanceOf(ActionException.class);
    }

    @Test
    void settingsRoundTrip() {
        assertThat(settings.getReportSettings(new GetReportSettingsInput(f.budgetId)))
                .isEqualTo(new ReportSettings(null, null, null, null, null, null, null));
        UUID shopping = f.category(f.group("Wants"), "Shopping");
        UUID amazon = f.payee("Amazon");
        ReportSettings s = new ReportSettings(List.of(shopping, shopping), 500_000L, List.of(), amazon, 20_000L,
                74_000_000L, null);
        settings.updateReportSettings(new UpdateReportSettingsInput(f.budgetId, s));
        assertThat(settings.getReportSettings(new GetReportSettingsInput(f.budgetId)))
                .isEqualTo(new ReportSettings(List.of(shopping), 500_000L, List.of(), amazon, 20_000L, 74_000_000L,
                        null));
        settings.updateReportSettings(new UpdateReportSettingsInput(f.budgetId, null));
        assertThat(settings.getReportSettings(new GetReportSettingsInput(f.budgetId)).thingsCategoryIds()).isNull();

        assertThatThrownBy(() -> settings.updateReportSettings(new UpdateReportSettingsInput(f.budgetId,
                new ReportSettings(List.of(UUID.randomUUID()), null, null, null, null, null, null))))
                .isInstanceOf(ActionException.class);
        assertThatThrownBy(() -> settings.updateReportSettings(new UpdateReportSettingsInput(f.budgetId,
                new ReportSettings(null, -1L, null, null, null, null, null))))
                .isInstanceOf(ActionException.class);
    }
}
