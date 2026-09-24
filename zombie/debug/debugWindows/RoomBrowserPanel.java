package zombie.debug.debugWindows;

import imgui.ImGui;
import imgui.flag.ImGuiSelectableFlags;
import imgui.flag.ImGuiTableFlags;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugContext;
import zombie.iso.IsoThermalRoom;
import zombie.iso.weather.RoomTemperatureManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RoomBrowserPanel extends PZDebugWindow {

    private static final int TABLE_FLAGS = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.Resizable;

    private final ArrayList<RoomThermalPanel> panels;

    public RoomBrowserPanel() {
        this.panels = new ArrayList<>();
    }

    @Override
    public String getTitle() {
        return "Room Browser";
    }

    @Override
    protected void doWindowContents() {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            ImGui.text("No local player.");
            return;
        }

        List<IsoThermalRoom> rooms = new ArrayList<>(RoomTemperatureManager.getInstance().getSimulatedRooms());
        rooms.sort(Comparator
                .<IsoThermalRoom>comparingInt(room -> (int) Math.abs(room.getZ() - player.getZ()))
                .thenComparingDouble(room -> horizontalDistanceSquaredToPlayer(room, player)));

        ImGui.text("Simulated rooms: " + rooms.size());
        ImGui.separator();
        if (ImGui.beginTable("RoomBrowserTable", 4, TABLE_FLAGS)) {
            ImGui.tableSetupColumn("Room ID");
            ImGui.tableSetupColumn("Type");
            ImGui.tableSetupColumn("Coordinates");
            ImGui.tableSetupColumn("Current Temp");
            ImGui.tableHeadersRow();
            for (IsoThermalRoom room : rooms) {
                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0);
                boolean clicked = ImGui.selectable(String.valueOf(room.getId()), false, ImGuiSelectableFlags.SpanAllColumns);
                boolean hovered = ImGui.isItemHovered();
                ImGui.tableSetColumnIndex(1);
                ImGui.text(room.isPlayerRoom() ? "Player-built" : "Mapped");
                ImGui.tableSetColumnIndex(2);
                ImGui.text(String.format("%d, %d, %d", room.getX(), room.getY(), room.getZ()));
                ImGui.tableSetColumnIndex(3);
                ImGui.text(String.format("%.2f °C", room.getCurrentTemperature()));

                if (hovered) {
                    RoomThermalPanel.highlightRoomBounds(room.getDebugInfo(), 0.36f, 0.9f, 0.42f, 0.35f, false);
                }
                if (clicked) {
                    RoomThermalPanel panel = this.getOrCreateRoomThermalPanel(room.getId());
                    if (!DebugContext.instance.getTransientWindows().contains(panel)) {
                        DebugContext.instance.getTransientWindows().add(panel);
                    }
                    panel.open();
                }
            }
            ImGui.endTable();
        }
    }

    private static double horizontalDistanceSquaredToPlayer(IsoThermalRoom room, IsoPlayer player) {
        double dx = room.getX() - player.getX();
        double dy = room.getY() - player.getY();

        return dx * dx + dy * dy;
    }

    private RoomThermalPanel getOrCreateRoomThermalPanel(long id) {
        RoomThermalPanel panel = this.panels.stream().filter(p -> p.getSelectedRoomId() == id).findFirst().orElse(null);
        if (panel == null) {
            panel = new RoomThermalPanel(id);
            panels.add(panel);
        }
        return panel;
    }

    public void removePanel(RoomThermalPanel panel) {
        this.panels.remove(panel);
    }
}
