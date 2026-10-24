package io.ynay.device;

import io.ynay.action.ActionException;
import io.ynay.action.ActionParam;
import io.ynay.action.McpAction;
import io.ynay.budget.BudgetAccess;
import io.ynay.budget.Inputs;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Devices for the tech replacement budget: what each costs per month if replaced only when worn out. */
@Component
public class DeviceActions {

    /** {@code Device}. monthlyCost = price / (lifespanYears * 12); replaceOn = purchasedOn + lifespanYears. */
    public record DeviceDto(UUID id, String name, long price, LocalDate purchasedOn, int lifespanYears,
                            boolean active, String note, long monthlyCost, LocalDate replaceOn) {
    }

    private final BudgetAccess access;
    private final JdbcClient jdbc;

    public DeviceActions(BudgetAccess access, JdbcClient jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    public record ListDevicesInput(@ActionParam("Budget id") UUID budgetId) {
    }

    @McpAction(name = "list_devices", readOnly = true, description = """
            List the devices in the tech replacement budget, most expensive first. Each has price \
            (milliunits), purchasedOn, lifespanYears, active (false = left out of the totals), \
            monthlyCost = price / (lifespanYears * 12) and replaceOn = purchasedOn + lifespanYears.""")
    public List<DeviceDto> listDevices(ListDevicesInput in) {
        access.requireMember(in.budgetId());
        return jdbc.sql("select * from device where budget_id = ? order by price desc, lower(name)")
                .param(in.budgetId())
                .query((rs, n) -> dto(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("price"),
                        rs.getObject("purchased_on", LocalDate.class), rs.getInt("lifespan_years"),
                        rs.getBoolean("active"), rs.getString("note")))
                .list();
    }

    public record CreateDeviceInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Name, e.g. \"iPhone 17\"") String name,
            @ActionParam("Price paid in milliunits") Long price,
            @ActionParam("Purchase date, YYYY-MM-DD") LocalDate purchasedOn,
            @ActionParam("Expected lifespan in years (1-50)") Integer lifespanYears,
            @ActionParam(value = "Count it in the totals (default true)", required = false) Boolean active,
            @ActionParam(value = "Note", required = false) String note) {
    }

    @McpAction(name = "create_device", description = """
            Add a device to the tech replacement budget (price in milliunits, purchase date, expected \
            lifespan in years). Purchases can be found with report_payments.""")
    public DeviceDto createDevice(CreateDeviceInput in) {
        access.requireMember(in.budgetId());
        String name = Inputs.requireName(in.name(), "name");
        if (in.purchasedOn() == null) {
            throw ActionException.invalid("purchasedOn is required");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into device (id, budget_id, name, price, purchased_on, lifespan_years, active, note)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, in.budgetId(), name, price(in.price()), in.purchasedOn(), lifespan(in.lifespanYears()),
                        in.active() == null || in.active(), Inputs.blankToNull(in.note()))
                .update();
        return require(in.budgetId(), id);
    }

    public record UpdateDeviceInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Device id") UUID deviceId,
            @ActionParam(value = "New name", required = false) String name,
            @ActionParam(value = "New price in milliunits", required = false) Long price,
            @ActionParam(value = "New purchase date", required = false) LocalDate purchasedOn,
            @ActionParam(value = "New lifespan in years", required = false) Integer lifespanYears,
            @ActionParam(value = "Count it in the totals", required = false) Boolean active,
            @ActionParam(value = "New note (\"\" clears it)", required = false) String note) {
    }

    @McpAction(name = "update_device", description = "Change a device; omitted fields stay unchanged.")
    public DeviceDto updateDevice(UpdateDeviceInput in) {
        access.requireMember(in.budgetId());
        DeviceDto d = require(in.budgetId(), in.deviceId());
        jdbc.sql("""
                        update device set name = ?, price = ?, purchased_on = ?, lifespan_years = ?, active = ?, note = ?
                        where id = ?""")
                .params(in.name() == null ? d.name() : Inputs.requireName(in.name(), "name"),
                        in.price() == null ? d.price() : price(in.price()),
                        in.purchasedOn() == null ? d.purchasedOn() : in.purchasedOn(),
                        in.lifespanYears() == null ? d.lifespanYears() : lifespan(in.lifespanYears()),
                        in.active() == null ? d.active() : in.active(),
                        in.note() == null ? d.note() : Inputs.blankToNull(in.note()),
                        d.id())
                .update();
        return require(in.budgetId(), d.id());
    }

    public record DeleteDeviceInput(
            @ActionParam("Budget id") UUID budgetId,
            @ActionParam("Device id") UUID deviceId) {
    }

    @McpAction(name = "delete_device", destructive = true, description = "Remove a device from the replacement budget.")
    public Map<String, Object> deleteDevice(DeleteDeviceInput in) {
        access.requireMember(in.budgetId());
        DeviceDto d = require(in.budgetId(), in.deviceId());
        jdbc.sql("delete from device where id = ?").param(d.id()).update();
        return Map.of("deleted", true);
    }

    private DeviceDto require(UUID budgetId, UUID id) {
        if (id == null) {
            throw ActionException.invalid("deviceId is required");
        }
        return jdbc.sql("select * from device where budget_id = ? and id = ?")
                .params(budgetId, id)
                .query((rs, n) -> dto(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("price"),
                        rs.getObject("purchased_on", LocalDate.class), rs.getInt("lifespan_years"),
                        rs.getBoolean("active"), rs.getString("note")))
                .optional()
                .orElseThrow(() -> ActionException.notFound("Device " + id));
    }

    private static DeviceDto dto(UUID id, String name, long price, LocalDate purchasedOn, int years, boolean active,
                                 String note) {
        return new DeviceDto(id, name, price, purchasedOn, years, active, note, Math.round(price / (years * 12.0)),
                purchasedOn.plusYears(years));
    }

    private static long price(Long price) {
        if (price == null || price < 0) {
            throw ActionException.invalid("price must be 0 or more (milliunits)");
        }
        return price;
    }

    private static int lifespan(Integer years) {
        if (years == null || years < 1 || years > 50) {
            throw ActionException.invalid("lifespanYears must be between 1 and 50");
        }
        return years;
    }
}
