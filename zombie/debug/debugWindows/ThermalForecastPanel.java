package zombie.debug.debugWindows;

import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.extension.implot.ImPlot;
import imgui.extension.implot.flag.ImPlotAxisFlags;
import imgui.extension.implot.flag.ImPlotCol;
import imgui.extension.implot.flag.ImPlotFlags;
import imgui.extension.implot.flag.ImPlotLocation;
import imgui.extension.implot.flag.ImPlotOrientation;
import imgui.flag.ImGuiCond;
import zombie.debug.DebugContext;
import zombie.iso.IsoThermalRoom;
import zombie.iso.weather.dbg.ThermalForecast;

import java.util.Optional;

/** Shows the 24h forecast for whichever room its owning {@link RoomThermalPanel} currently shows. */
public class ThermalForecastPanel extends PZDebugWindow {

    private static final float MIN_WIDTH = 350;
    private static final float MIN_HEIGHT = 250;

    private final RoomThermalPanel owner;

    ThermalForecastPanel(RoomThermalPanel owner) {
        this.owner = owner;
    }

    @Override
    public String getTitle() {
        // Unique per owner: the default panel's forecast vs. one per pinned room
        Long pinned = this.owner.getSelectedRoomId();
        return pinned != null ? "Thermal Forecast - " + pinned : "Thermal Forecast";
    }

    @Override
    public void doWindow() {
        // Snap to the owner's left edge every frame so both windows move together;
        // flip to the owner's right side if it would run off the screen
        float width = this.width > 0 ? this.width : 500;
        float x = this.owner.getWindowLeft() - width;
        if (x < 0) x = this.owner.getWindowRight();

        ImGui.setNextWindowPos(x, this.owner.getWindowTop(), ImGuiCond.Always);
        ImGui.setNextWindowSizeConstraints(MIN_WIDTH, MIN_HEIGHT, Float.MAX_VALUE, Float.MAX_VALUE);
        super.doWindow();
    }

    void open() {
        if (!DebugContext.instance.getTransientWindows().contains(this)) {
            DebugContext.instance.getTransientWindows().add(this);
        }
        this.open.set(true);
    }

    /** Closes the window from outside (owner closed); onCloseWindow won't fire because a closed window isn't drawn. */
    void close() {
        this.open.set(false);
        this.wasWindowOpened = false;
        DebugContext.instance.closeTransient(this);
    }

    @Override
    protected void onCloseWindow() {
        super.onCloseWindow();
        DebugContext.instance.closeTransient(this);
    }

    @Override
    protected void doWindowContents() {
        Optional<IsoThermalRoom> optional = this.owner.resolveRoom();
        if (optional.isEmpty()) {
            ImGui.text(this.owner.describeMissingRoom());
            return;
        }
        IsoThermalRoom room = optional.get();
        ThermalForecast.Result result = room.enableDebugInfo().getForecast();
        if (result == null) {
            ImGui.textDisabled("Waiting for server...");
            return;
        }

        ImGui.text(String.format("Room %d at (%d, %d, %d)", room.getId(), room.getX(), room.getY(), room.getZ()));
        ImGui.text(String.format("Simulating %d connected room%s", result.roomCount(), result.roomCount() == 1 ? "" : "s"));
        ImGui.separator();

        double minY = result.minTemp(), maxY = result.maxTemp();
        for (double outdoor : result.outdoorTemps()) {
            minY = Math.min(minY, outdoor);
            maxY = Math.max(maxY, outdoor);
        }

        // Plot fills the window, leaving room for the three text lines below it
        float footer = ImGui.getTextLineHeightWithSpacing() * 3;
        ImPlot.setNextPlotLimits(0, ThermalForecast.HORIZON_HOURS, minY - 1.0, maxY + 1.0, 1);
        if (ImPlot.beginPlot("##forecast", "Hours from now", "°C", new ImVec2(-1, -footer),
                ImPlotFlags.NoTitle | ImPlotFlags.NoMenus | ImPlotFlags.NoBoxSelect,
                ImPlotAxisFlags.None, ImPlotAxisFlags.None)) {
            ImPlot.setLegendLocation(ImPlotLocation.South, ImPlotOrientation.Horizontal, true);
            int count = result.hours().length;
            pushPlotColor(ImPlotCol.Line, 0.6f, 0.6f, 0.6f, 1.0f);
            ImPlot.plotLine("Outdoor", result.hours(), result.outdoorTemps(), count, 0);
            ImPlot.popStyleColor();
            pushPlotColor(ImPlotCol.Line, 0.3f, 0.7f, 1.0f, 1.0f);
            ImPlot.plotLine("Room", result.hours(), result.roomTemps(), count, 0);
            ImPlot.popStyleColor();
            ImPlot.plotText(String.format("%.1f", result.maxTemp()), result.maxHour(), result.maxTemp() + 0.5);
            ImPlot.plotText(String.format("%.1f", result.minTemp()), result.minHour(), result.minTemp() - 0.5);
            ImPlot.endPlot();
        }

        ImGui.text(String.format("Highest: %.2f °C in %s", result.maxTemp(), formatHours(result.maxHour())));
        ImGui.text(String.format("Lowest:  %.2f °C in %s", result.minTemp(), formatHours(result.minHour())));
        ImGui.textDisabled("Assumes doors, windows and heat sources stay as they are now.");
    }

    private static String formatHours(double hours) {
        int totalMinutes = (int) Math.round(hours * 60);
        return String.format("%dh %02dm", totalMinutes / 60, totalMinutes % 60);
    }

    private static void pushPlotColor(int plotCol, float r, float g, float b, float a) {
        ImPlot.pushStyleColor(plotCol, new ImVec4(g, b, a, r)); // same channel-order workaround as RoomThermalPanel
    }
}
