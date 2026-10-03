package zombie.debug.debugWindows;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.type.ImDouble;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import zombie.iso.weather.RoomTemperatureManager;

import java.util.HashMap;

public class ThermalConfigPanel extends PZDebugWindow {

    private final HashMap<String, Number> buffers = new HashMap<>();
    private int seenRevision = -1;

    @Override
    public String getTitle() {
        return "Thermal Config";
    }

    @Override
    protected void doWindowContents() {
        if (this.seenRevision != RoomTemperatureManager.ThermalConfig.getRevision()) {
            this.seenRevision = RoomTemperatureManager.ThermalConfig.getRevision();
            this.buffers.clear();
        }
        this.renderConfigEditor();
    }

    private void renderConfigEditor() {
        ImGui.text("Global modifiers");
        ImGui.separator();
        RoomTemperatureManager.ThermalConfig.OPTIONS.stream()
                .filter(o -> o.type() == RoomTemperatureManager.ThermalConfig.Option.Type.GLOBAL)
                .forEach(this::renderField);
        ImGui.text("Room modifiers");
        ImGui.separator();
        RoomTemperatureManager.ThermalConfig.OPTIONS.stream()
                .filter(o -> o.type() == RoomTemperatureManager.ThermalConfig.Option.Type.ROOM)
                .forEach(this::renderField);
        ImGui.text("Environment modifiers");
        ImGui.separator();
        RoomTemperatureManager.ThermalConfig.OPTIONS.stream()
                .filter(o -> o.type() == RoomTemperatureManager.ThermalConfig.Option.Type.ENVIRONMENT)
                .forEach(this::renderField);
        ImGui.text("RV Interior modifiers");
        ImGui.separator();
        RoomTemperatureManager.ThermalConfig.OPTIONS.stream()
                .filter(o -> o.type() == RoomTemperatureManager.ThermalConfig.Option.Type.RV)
                .forEach(this::renderField);
        ImGui.separator();
        ImGui.pushStyleColor(ImGuiCol.Button, 40, 148, 71, 255);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 59, 184, 94, 255);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 105, 245, 145, 255);
        if (ImGui.button("Save")) {
            RoomTemperatureManager.ThermalConfig.requestSave();
        }
        ImGui.popStyleColor(3);
        ImGui.sameLine();
        ImGui.pushStyleColor(ImGuiCol.Button, 168, 62, 44, 255);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 207, 84, 62, 255);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 237, 82, 55, 255);
        if (ImGui.button("Restore")) {
            RoomTemperatureManager.ThermalConfig.requestRestore();
        }
        ImGui.popStyleColor(3);
        ImGui.sameLine();
        ImGui.pushStyleColor(ImGuiCol.Button, 255, 201, 96, 255);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 255, 191, 66, 255);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 255, 225, 167, 255);
        if (ImGui.button("Reset")) {
            RoomTemperatureManager.ThermalConfig.requestReset();
        }
        ImGui.popStyleColor(3);
    }

    private void renderField(RoomTemperatureManager.ThermalConfig.Option<?> option) {
        if (option.defaultValue() instanceof Integer) {
            ImInt i = (ImInt) this.buffers.computeIfAbsent(option.name(), _ -> new ImInt(option.get().intValue()));
            ImGui.setNextItemWidth(-300);
            if (ImGui.inputInt(option.name(), i, 0, 0)) RoomTemperatureManager.ThermalConfig.edit(option, i.get());
            if (ImGui.isItemHovered() && !option.description().isEmpty()) ImGui.setTooltip(option.description());
        } else if (option.defaultValue() instanceof Float) {
            ImFloat f = (ImFloat) this.buffers.computeIfAbsent(option.name(), _ -> new ImFloat(option.get().floatValue()));
            ImGui.setNextItemWidth(-300);
            if (ImGui.inputFloat(option.name(), f, 0, 0, "%.3f")) RoomTemperatureManager.ThermalConfig.edit(option, f.get());
            if (ImGui.isItemHovered() && !option.description().isEmpty()) ImGui.setTooltip(option.description());
        } else if (option.defaultValue() instanceof Double) {
            ImDouble d = (ImDouble) this.buffers.computeIfAbsent(option.name(), _ -> new ImDouble(option.get().doubleValue()));
            ImGui.setNextItemWidth(-300);
            if (ImGui.inputDouble(option.name(), d, 0, 0, "%.3f")) RoomTemperatureManager.ThermalConfig.edit(option, d.get());
            if (ImGui.isItemHovered() && !option.description().isEmpty()) ImGui.setTooltip(option.description());
        }
    }

}
