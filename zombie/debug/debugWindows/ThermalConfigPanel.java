package zombie.debug.debugWindows;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.type.ImDouble;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import zombie.iso.weather.RoomTemperatureManager;

import java.util.HashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ThermalConfigPanel extends PZDebugWindow {

    private final HashMap<String, ImFloat> floatBuffers = new HashMap<>();
    private final HashMap<String, ImDouble> doubleBuffers = new HashMap<>();
    private final HashMap<String, ImInt> intBuffers = new HashMap<>();

    @Override
    public String getTitle() {
        return "Thermal Config";
    }

    @Override
    protected void doWindowContents() {
        this.renderConfigEditor();
    }

    private void renderConfigEditor() {
        ImGui.text("Global modifiers");
        ImGui.separator();
        this.renderDoubleField("PLAYER_ROOM_STALE_MATCH_RADIUS",
                () -> RoomTemperatureManager.ThermalConfig.PLAYER_ROOM_STALE_MATCH_RADIUS,
                v -> RoomTemperatureManager.ThermalConfig.PLAYER_ROOM_STALE_MATCH_RADIUS = v
        );
        this.renderDoubleField("ROOM_SYNC_RELEVANCE_RADIUS",
                () -> RoomTemperatureManager.ThermalConfig.ROOM_SYNC_RELEVANCE_RADIUS,
                v -> RoomTemperatureManager.ThermalConfig.ROOM_SYNC_RELEVANCE_RADIUS = v
        );
        this.renderFloatField("TEMP_SYNC_EPSILON",
                () -> RoomTemperatureManager.ThermalConfig.TEMP_SYNC_EPSILON,
                v -> RoomTemperatureManager.ThermalConfig.TEMP_SYNC_EPSILON = v
        );
        this.renderIntField("ROOM_REQUEST_COOLDOWN",
                () -> RoomTemperatureManager.ThermalConfig.ROOM_REQUEST_COOLDOWN,
                v -> RoomTemperatureManager.ThermalConfig.ROOM_REQUEST_COOLDOWN = v
        );
        this.renderDoubleField("OUTDOOR_SAMPLE_INTERVAL_HOURS",
                () -> RoomTemperatureManager.ThermalConfig.OUTDOOR_SAMPLE_INTERVAL_HOURS,
                v -> RoomTemperatureManager.ThermalConfig.OUTDOOR_SAMPLE_INTERVAL_HOURS = v
        );
        this.renderDoubleField("OUTDOOR_HISTORY_MAX_HOURS",
                () -> RoomTemperatureManager.ThermalConfig.OUTDOOR_HISTORY_MAX_HOURS,
                v -> RoomTemperatureManager.ThermalConfig.OUTDOOR_HISTORY_MAX_HOURS = v
        );
        this.renderDoubleField("TEMP_APPLY_INTERVAL_HOURS",
                () -> RoomTemperatureManager.ThermalConfig.TEMP_APPLY_INTERVAL_HOURS,
                v -> RoomTemperatureManager.ThermalConfig.TEMP_APPLY_INTERVAL_HOURS = v
        );
        this.renderDoubleField("TEMP_CALCULATE_INTERVAL_HOURS",
                () -> RoomTemperatureManager.ThermalConfig.TEMP_CALCULATE_INTERVAL_HOURS,
                v -> RoomTemperatureManager.ThermalConfig.TEMP_CALCULATE_INTERVAL_HOURS = v
        );
        ImGui.spacing();
        ImGui.text("Room Simulation modifiers");
        ImGui.separator();
        this.renderFloatField("BASE_COEFFICIENT",
                () -> RoomTemperatureManager.ThermalConfig.BASE_COEFFICIENT,
                v -> RoomTemperatureManager.ThermalConfig.BASE_COEFFICIENT = v
        );
        this.renderFloatField("WINDOW_CLOSED_COEFFICIENT",
                () -> RoomTemperatureManager.ThermalConfig.WINDOW_CLOSED_COEFFICIENT,
                v -> RoomTemperatureManager.ThermalConfig.WINDOW_CLOSED_COEFFICIENT = v
        );
        this.renderFloatField("WINDOW_CURTAIN_MULTIPLIER",
                () -> RoomTemperatureManager.ThermalConfig.WINDOW_CURTAIN_MULTIPLIER,
                v -> RoomTemperatureManager.ThermalConfig.WINDOW_CURTAIN_MULTIPLIER = v
        );
        this.renderFloatField("WINDOW_OPEN_COEFFICIENT",
                () -> RoomTemperatureManager.ThermalConfig.WINDOW_OPEN_COEFFICIENT,
                v -> RoomTemperatureManager.ThermalConfig.WINDOW_OPEN_COEFFICIENT = v
        );
        this.renderFloatField("BARRICADE_INSULATION_MULTIPLIER",
                () -> RoomTemperatureManager.ThermalConfig.BARRICADE_INSULATION_MULTIPLIER,
                v -> RoomTemperatureManager.ThermalConfig.BARRICADE_INSULATION_MULTIPLIER = v
        );
        this.renderFloatField("DOOR_CLOSED_COEFFICIENT",
                () -> RoomTemperatureManager.ThermalConfig.DOOR_CLOSED_COEFFICIENT,
                v -> RoomTemperatureManager.ThermalConfig.DOOR_CLOSED_COEFFICIENT = v
        );
        this.renderFloatField("DOOR_OPEN_COEFFICIENT",
                () -> RoomTemperatureManager.ThermalConfig.DOOR_OPEN_COEFFICIENT,
                v -> RoomTemperatureManager.ThermalConfig.DOOR_OPEN_COEFFICIENT = v
        );
        this.renderFloatField("BREACH_COEFFICIENT",
                () -> RoomTemperatureManager.ThermalConfig.BREACH_COEFFICIENT,
                v -> RoomTemperatureManager.ThermalConfig.BREACH_COEFFICIENT = v
        );
        this.renderFloatField("STAIR_LINK_COEFFICIENT",
                () -> RoomTemperatureManager.ThermalConfig.STAIR_LINK_COEFFICIENT,
                v -> RoomTemperatureManager.ThermalConfig.STAIR_LINK_COEFFICIENT = v
        );
        this.renderFloatField("HEATSOURCE_MIN_COEFFICIENT",
                () -> RoomTemperatureManager.ThermalConfig.HEATSOURCE_MIN_COEFFICIENT,
                v -> RoomTemperatureManager.ThermalConfig.HEATSOURCE_MIN_COEFFICIENT = v
        );
        this.renderFloatField("HEATSOURCE_MAX_COEFFICIENT",
                () -> RoomTemperatureManager.ThermalConfig.HEATSOURCE_MAX_COEFFICIENT,
                v -> RoomTemperatureManager.ThermalConfig.HEATSOURCE_MAX_COEFFICIENT = v
        );
        this.renderFloatField("HEATSOURCE_RADIUS_SCALE",
                () -> RoomTemperatureManager.ThermalConfig.HEATSOURCE_RADIUS_SCALE,
                v -> RoomTemperatureManager.ThermalConfig.HEATSOURCE_RADIUS_SCALE = v
        );
        this.renderFloatField("CLIMATE_CONTROL_COEFFICIENT",
                () -> RoomTemperatureManager.ThermalConfig.CLIMATE_CONTROL_COEFFICIENT,
                v -> RoomTemperatureManager.ThermalConfig.CLIMATE_CONTROL_COEFFICIENT = v
        );
        this.renderFloatField("TEMP_CHANGE_RATE_MULTIPLIER",
                () -> RoomTemperatureManager.ThermalConfig.TEMP_CHANGE_RATE_MULTIPLIER,
                v -> RoomTemperatureManager.ThermalConfig.TEMP_CHANGE_RATE_MULTIPLIER = v
        );
        this.renderFloatField("INTERROOM_TRANSFER_MULTIPLIER",
                () -> RoomTemperatureManager.ThermalConfig.INTERROOM_TRANSFER_MULTIPLIER,
                v -> RoomTemperatureManager.ThermalConfig.INTERROOM_TRANSFER_MULTIPLIER = v
        );
        this.renderFloatField("HEATING_RATE_MULTIPLIER",
                () -> RoomTemperatureManager.ThermalConfig.HEATING_RATE_MULTIPLIER,
                v -> RoomTemperatureManager.ThermalConfig.HEATING_RATE_MULTIPLIER = v
        );
        this.renderFloatField("COOLING_RATE_MULTIPLIER",
                () -> RoomTemperatureManager.ThermalConfig.COOLING_RATE_MULTIPLIER,
                v -> RoomTemperatureManager.ThermalConfig.COOLING_RATE_MULTIPLIER = v
        );
        this.renderFloatField("MAX_TEMP_DELTA",
                () -> RoomTemperatureManager.ThermalConfig.MAX_TEMP_DELTA,
                v -> RoomTemperatureManager.ThermalConfig.MAX_TEMP_DELTA = v
        );
        this.renderFloatField("UPPER_FLOOR_TEMP_DROP",
                () -> RoomTemperatureManager.ThermalConfig.UPPER_FLOOR_TEMP_DROP,
                v -> RoomTemperatureManager.ThermalConfig.UPPER_FLOOR_TEMP_DROP = v
        );
        this.renderFloatField("SOLAR_ROOF_GAIN",
                () -> RoomTemperatureManager.ThermalConfig.SOLAR_ROOF_GAIN,
                v -> RoomTemperatureManager.ThermalConfig.SOLAR_ROOF_GAIN = v
        );
        this.renderFloatField("GROUND_TEMPERATURE",
                () -> RoomTemperatureManager.ThermalConfig.GROUND_TEMPERATURE,
                v -> RoomTemperatureManager.ThermalConfig.GROUND_TEMPERATURE = v
        );
        this.renderFloatField("GROUND_OUTDOOR_FACTOR",
                () -> RoomTemperatureManager.ThermalConfig.GROUND_OUTDOOR_FACTOR,
                v -> RoomTemperatureManager.ThermalConfig.GROUND_OUTDOOR_FACTOR = v
        );
        this.renderFloatField("BASEMENT_GROUND_COEFFICIENT",
                () -> RoomTemperatureManager.ThermalConfig.BASEMENT_GROUND_COEFFICIENT,
                v -> RoomTemperatureManager.ThermalConfig.BASEMENT_GROUND_COEFFICIENT = v
        );
        this.renderFloatField("BASEMENT_OUTDOOR_FACTOR",
                () -> RoomTemperatureManager.ThermalConfig.BASEMENT_OUTDOOR_FACTOR,
                v -> RoomTemperatureManager.ThermalConfig.BASEMENT_OUTDOOR_FACTOR = v
        );
        this.renderFloatField("MIN_FLOOR_OUTDOOR_MULTIPLIER",
                () -> RoomTemperatureManager.ThermalConfig.MIN_FLOOR_OUTDOOR_MULTIPLIER,
                v -> RoomTemperatureManager.ThermalConfig.MIN_FLOOR_OUTDOOR_MULTIPLIER = v
        );
        ImGui.separator();
        ImGui.pushStyleColor(ImGuiCol.Button, 40, 148, 71, 255);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 59, 184, 94, 255);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 105, 245, 145, 255);
        if (ImGui.button("Save")) {
            RoomTemperatureManager.ThermalConfig.save();
        }
        ImGui.sameLine();
        ImGui.popStyleColor(3);
        ImGui.pushStyleColor(ImGuiCol.Button, 168, 62, 44, 255);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 207, 84, 62, 255);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 237, 82, 55, 255);
        if (ImGui.button("Reset")) {
            RoomTemperatureManager.ThermalConfig.load();
            this.floatBuffers.clear();
            this.doubleBuffers.clear();
            this.intBuffers.clear();
        }
        ImGui.popStyleColor(3);
    }

    private void renderFloatField(String label, Supplier<Float> supplier, Consumer<Float> consumer) {
        ImFloat buffer = this.floatBuffers.computeIfAbsent(label, _ -> new ImFloat(supplier.get()));
        ImGui.setNextItemWidth(-300);
        if (ImGui.inputFloat(label, buffer, 0, 0, "%.2f")) {
            consumer.accept(buffer.get());
        }
    }

    private void renderDoubleField(String label, Supplier<Double> supplier, Consumer<Double> consumer) {
        ImDouble buffer = this.doubleBuffers.computeIfAbsent(label, _ -> new ImDouble(supplier.get()));
        ImGui.setNextItemWidth(-300);
        if (ImGui.inputDouble(label, buffer, 0, 0, "%.2f")) {
            consumer.accept(buffer.get());
        }
    }

    private void renderIntField(String label, Supplier<Integer> supplier, Consumer<Integer> consumer) {
        ImInt buffer = this.intBuffers.computeIfAbsent(label, _ -> new ImInt(supplier.get()));
        ImGui.setNextItemWidth(-300);
        if (ImGui.inputInt(label, buffer, 0, 0)) {
            consumer.accept(buffer.get());
        }
    }

}
