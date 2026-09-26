package zombie.debug.debugWindows;

import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import zombie.GameTime;
import zombie.iso.weather.RoomTemperatureManager;
import zombie.network.GameClient;

import java.util.List;

public class PersistenceInspectorPanel extends PZDebugWindow {

    private static final int TABLE_FLAGS = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.Resizable;

    @Override
    public String getTitle() {
        return "Persistence Inspector";
    }

    @Override
    protected void doWindowContents() {
        RoomTemperatureManager.getInstance().requestPersistentThermalData();
        List<RoomTemperatureManager.PersistentThermalData> ptds = RoomTemperatureManager.getInstance().getPersistentThermalData();

        if (!RoomTemperatureManager.getInstance().hasPersistentThermalData()) {
            ImGui.textDisabled("Waiting for server...");
            return;
        }

        double now = GameTime.getInstance().getWorldAgeHours();
        double maxAge = RoomTemperatureManager.ThermalConfig.PERSISTENT_THERMAL_DATA_MAX_AGE;

        ImGui.text("Stale entries: " + ptds.size());
        ImGui.text(String.format("Max age before cleanup: %.0f hours", maxAge));
        ImGui.separator();
        if (ImGui.button("Force Clear")) {
            RoomTemperatureManager.getInstance().clearPersistentThermalData();
        }
        ImGui.separator();

        if (ImGui.beginTable("StaleRoomsTable", 5, TABLE_FLAGS)) {
            ImGui.tableSetupColumn("Coords");
            ImGui.tableSetupColumn("Type");
            ImGui.tableSetupColumn("Last Temp");
            ImGui.tableSetupColumn("Age (hrs)");
            ImGui.tableSetupColumn("Expires in (hrs)");
            ImGui.tableHeadersRow();

            for (RoomTemperatureManager.PersistentThermalData ptd : ptds) {
                double age = now - ptd.lastUpdate();
                double expiresIn = maxAge - age;

                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0);
                ImGui.text(String.format("%d, %d, %d", ptd.x(), ptd.y(), ptd.z()));
                ImGui.tableSetColumnIndex(1);
                ImGui.text(ptd.isPlayerRoom() ? "Player-built" : "Mapped");
                ImGui.tableSetColumnIndex(2);
                ImGui.text(String.format("%.2f °C", ptd.lastTemp()));
                ImGui.tableSetColumnIndex(3);
                ImGui.text(String.format("%.1f", age));
                ImGui.tableSetColumnIndex(4);
                if (expiresIn <= 24) {
                    ImGui.textColored(1.0f, 0.4f, 0.2f, 1.0f, String.format("%.1f", expiresIn));
                } else {
                    ImGui.text(String.format("%.1f", expiresIn));
                }
            }
            ImGui.endTable();
        }
    }
}
