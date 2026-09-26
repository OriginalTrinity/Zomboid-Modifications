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
import imgui.extension.implot.flag.ImPlotStyleVar;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImFloat;
import org.jetbrains.annotations.Nullable;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugContext;
import zombie.debug.DebugType;
import zombie.iso.*;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.fboRenderChunk.FBORenderAreaHighlights;
import zombie.iso.objects.IsoBarbecue;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireplace;
import zombie.iso.weather.RoomTemperatureManager;
import zombie.network.packets.RoomThermalDebugPacket;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

public class RoomThermalPanel extends PZDebugWindow {

    private static final int TABLE_FLAGS = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.Resizable;
    private static final String[] CONTRIBUTION_LABELS = {"Base", "Windows", "Openings", "Stairs", "Heatsources", "Ground", "Climate Control"};
    private static final float[][] CONTRIBUTION_COLORS = {
            {1.0f, 1.0f, 1.0f}, // Base - white, matches room bounds fill
            {0.2f, 0.9f, 0.9f}, // Windows - cyan
            {1.0f, 0.6f, 0.1f}, // Openings - orange, matches door highlight
            {0.8f, 0.2f, 0.8f}, // Stairs - purple
            {1.0f, 0.3f, 0.0f}, // Heatsources - deep orange
            {0.55f, 0.35f, 0.2f}, // Ground - brown
            {0.3f, 0.5f, 1.0f}, // Climate control - blue
    };
    private static final DecimalFormat CONTRIBUTION_VALUE_FORMAT = new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.US));

    private Long selectedRoomId;
    private final ImFloat roomTempInput;
    private boolean showWorldOverlay;
    private ThermalForecastPanel forecastPanel;

    public RoomThermalPanel() {
        this.selectedRoomId = null;
        this.roomTempInput = new ImFloat();
        this.showWorldOverlay = false;
    }

    public RoomThermalPanel(Long roomId) {
        this();
        this.selectedRoomId = roomId;
    }

    @Override
    public String getTitle() {
        return this.selectedRoomId != null ? "Room Thermal Simulation - " + this.selectedRoomId : "Room Thermal Simulation";
    }

    @Override
    protected void doWindowContents() {
        Optional<IsoThermalRoom> optional = this.resolveRoom();
        if (optional.isEmpty()) {
            ImGui.text(this.describeMissingRoom());
            return;
        }
        this.renderDebugInfo(optional.get());
    }

    /** The room this panel currently shows: the pinned room, or the one the player is standing in. */
    Optional<IsoThermalRoom> resolveRoom() {
        if (this.selectedRoomId != null) {
            return RoomTemperatureManager.getInstance().getSimulatedRoomById(this.selectedRoomId);
        }
        IsoPlayer player = IsoPlayer.getInstance();
        IsoGridSquare square = player != null ? player.getCurrentSquare() : null;
        return square != null ? RoomTemperatureManager.getInstance().getSimulatedRoom(square) : Optional.empty();
    }

    float getWindowLeft() {
        return this.x;
    }

    float getWindowRight() {
        return this.x + this.width;
    }

    float getWindowTop() {
        return this.y;
    }

    String describeMissingRoom() {
        if (this.selectedRoomId != null) return "Selected room is no longer simulated.";
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) return "No local player.";
        if (player.getCurrentSquare() == null) return "Not on a valid grid square.";
        return "Not currently in a simulated room.";
    }

    @Override
    protected void onCloseWindow() {
        super.onCloseWindow();
        if (this.forecastPanel != null) this.forecastPanel.close();

        DebugContext.instance.getWindows().stream().filter(window -> window instanceof RoomBrowserPanel).findFirst().ifPresent(window -> {
            RoomBrowserPanel browserPanel = (RoomBrowserPanel) window;
            browserPanel.removePanel(this);
        });
        DebugContext.instance.getTransientWindows().remove(this);
    }

    private void renderDebugInfo(IsoThermalRoom room) {
        IsoThermalRoom.DebugInfo debugInfo = room.enableDebugInfo();
        debugInfo.requestRefresh();

        ImGui.text("Room ID: " + room.getId());
        ImGui.text("Type: " + (room.isPlayerRoom() ? "Player-built" : "Mapped"));
        ImGui.text(String.format("Coords: (%d, %d, %d)", room.getX(), room.getY(), room.getZ()));
        ImGui.separator();

        if (!debugInfo.hasData()) {
            ImGui.textDisabled("Waiting for server...");
            return;
        }

        ImGui.text(String.format("Outside Temp: %.2f °C", debugInfo.getOutsideTemp()));

        if (ImGui.treeNode("currentTempNode", String.format("Current Temp: %.2f °C", room.getCurrentTemperature()))) {
            this.renderTempHistoryGraph(debugInfo.getCurrentTempHistory(), debugInfo.getHistorySize(), debugInfo.getHistoryWriteIndex(), 0.3f, 0.7f, 1.0f);
            ImGui.treePop();
        }

        if (ImGui.treeNode("targetTempNode", String.format("Target Temp: %.2f °C", room.getTargetTemperature()))) {
            this.renderTempHistoryGraph(debugInfo.getTargetTempHistory(), debugInfo.getHistorySize(), debugInfo.getHistoryWriteIndex(), 1.0f, 0.65f, 0.2f);
            ImGui.treePop();
        }

        if (room.getZ() > 0) {
            ImGui.text(String.format("Z-level influence: -%.2f °C", debugInfo.getFloorTempDrop()));
        }
        if (room.getZ() < 0) {
            ImGui.text(String.format("Ground Temp: %.2f °C", debugInfo.getGroundTemp()));
            ImGui.text(String.format("Outdoor multiplier: %.2f", debugInfo.getFloorOutdoorMultiplier()));
        }
        ImGui.text(String.format("Roof exposure: %.0f%% (sun %.2f -> +%.2f °C)",
                room.getRoofFraction() * 100.0f,
                debugInfo.getSunStrength(),
                debugInfo.getSolarGain()));

        if (ImGui.button("Open 24h Forecast")) {
            if (this.forecastPanel == null) this.forecastPanel = new ThermalForecastPanel(this);
            this.forecastPanel.open();
        }

        ImGui.text("Squares: " + debugInfo.getTiles().size());
        ImGui.separator();
        ImGui.text(String.format("Weight sum: %.4f", room.getWeightSum()));
        if (ImGui.isItemHovered()) {
            this.renderWeightContributionsTooltip(debugInfo);
        }
        ImGui.text(String.format("Weighted sum: %.4f", room.getWeightedSum()));
        ImGui.text(String.format("Temp delta: %.4f", debugInfo.getTempChangeDelta()));
        this.showWorldOverlay = PZImGui.checkbox("Show In-World Overlay", this.showWorldOverlay);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("white=room, cyan=window, orange=door, red=breach, purple=stairs, deep orange=heat source");
        }
        if (this.showWorldOverlay) {
            this.renderWorldOverlay(room);
        }
        ImGui.separator();

        List<IsoThermalRoom.DebugInfo.Opening> exteriorWindows = debugInfo.getOpenings().stream().filter(IsoThermalRoom.DebugInfo.Opening::outdoorWindow).toList();
        ImGui.text("Exterior windows (" + exteriorWindows.size() + ")");
        if (ImGui.beginTable("Windows", 5, TABLE_FLAGS)) {
            ImGui.tableSetupColumn("Square");
            ImGui.tableSetupColumn("State");
            ImGui.tableSetupColumn("Curtains");
            ImGui.tableSetupColumn("# Barricades");
            ImGui.tableSetupColumn("Coefficient");
            ImGui.tableHeadersRow();
            for (IsoThermalRoom.DebugInfo.Opening opening : exteriorWindows) {
                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0);
                ImGui.text(opening.x() + "," + opening.y());
                ImGui.tableSetColumnIndex(1);
                ImGui.text(opening.state());
                ImGui.tableSetColumnIndex(2);
                ImGui.text(opening.curtains());
                ImGui.tableSetColumnIndex(3);
                ImGui.text(opening.barricades());
                ImGui.tableSetColumnIndex(4);
                ImGui.text(String.format("%.3f", opening.coefficient()));
            }
            ImGui.endTable();
        }
        ImGui.separator();

        ImGui.text("Openings (" + debugInfo.getOpenings().size() + ")");
        if (ImGui.beginTable("RoomOpenings", 6, TABLE_FLAGS)) {
            ImGui.tableSetupColumn("Square");
            ImGui.tableSetupColumn("Type");
            ImGui.tableSetupColumn("State");
            ImGui.tableSetupColumn("Faces");
            ImGui.tableSetupColumn("Coefficient");
            ImGui.tableSetupColumn("Other temp");
            ImGui.tableHeadersRow();
            for (IsoThermalRoom.DebugInfo.Opening opening : debugInfo.getOpenings()) {
                if (opening.outdoorWindow() || Float.isNaN(opening.otherTemp())) continue;
                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0);
                ImGui.text(opening.x() + "," + opening.y());
                ImGui.tableSetColumnIndex(1);
                ImGui.text(opening.type());
                ImGui.tableSetColumnIndex(2);
                ImGui.text(opening.state());
                ImGui.tableSetColumnIndex(3);
                ImGui.text(opening.facing());
                ImGui.tableSetColumnIndex(4);
                ImGui.text(String.format("%.3f", opening.coefficient()));
                ImGui.tableSetColumnIndex(5);
                ImGui.text(String.format("%.2f °C", opening.otherTemp()));
            }
            ImGui.endTable();
        }
        ImGui.separator();

        ImGui.text("Stairs (" + debugInfo.getStairs().size() + ")");
        if (ImGui.beginTable("Stairs", 4, TABLE_FLAGS)) {
            ImGui.tableSetupColumn("Bottom square");
            ImGui.tableSetupColumn("Top square");
            ImGui.tableSetupColumn("Facing");
            ImGui.tableSetupColumn("Other temp");
            ImGui.tableHeadersRow();

            for (IsoThermalRoom.DebugInfo.Stair stair : debugInfo.getStairs()) {
                boolean resolved = stair.otherRoomId() != -1;
                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0);
                ImGui.text(stair.bottomX() + "," + stair.bottomY() + "," + stair.bottomZ());
                ImGui.tableSetColumnIndex(1);
                ImGui.text(stair.topX() + "," + stair.topY() + "," + stair.topZ());
                ImGui.tableSetColumnIndex(2);
                ImGui.text(resolved ? "Room " + stair.otherRoomId() : "?");
                ImGui.tableSetColumnIndex(3);
                ImGui.text(resolved ? String.format("%.2f °C", stair.otherTemp()) : "?");
            }
            ImGui.endTable();
        }
        ImGui.separator();

        ImGui.text("Heatsources (" + debugInfo.getHeatSources().size() + ")");
        if (ImGui.beginTable("HeatSources", 4, TABLE_FLAGS)) {
            ImGui.tableSetupColumn("Square");
            ImGui.tableSetupColumn("Radius");
            ImGui.tableSetupColumn("Temperature");
            ImGui.tableSetupColumn("Coefficient");
            ImGui.tableHeadersRow();
            for (IsoThermalRoom.DebugInfo.HeatSource heatSource : debugInfo.getHeatSources()) {
                ImGui.tableNextRow();
                ImGui.tableSetColumnIndex(0);
                ImGui.text(heatSource.x() + "," + heatSource.y());
                ImGui.tableSetColumnIndex(1);
                ImGui.text(String.valueOf(heatSource.radius()));
                ImGui.tableSetColumnIndex(2);
                ImGui.text(String.format("%d °C", heatSource.temperature()));
                ImGui.tableSetColumnIndex(3);
                ImGui.text(String.format("%.3f", heatSource.coefficient()));
            }
            ImGui.endTable();
        }
        ImGui.separator();
        if (ImGui.button("Force Re-scan")) {
            debugInfo.runAction(RoomThermalDebugPacket.ACTION_RESCAN, 0f);
        }
        ImGui.sameLine();
        if (ImGui.button("Dump Tile Info")) {
            IsoPlayer player = IsoPlayer.getInstance();
            IsoGridSquare square = player.getCurrentSquare();
            if (square == null) return;
            this.dumpTile("SELF", square);
            IsoCell cell = IsoWorld.instance.getCell();
            if (cell != null) {
                this.dumpTile("NORTH", cell.getGridSquare(square.getX(), square.getY() - 1, square.getZ()));
                this.dumpTile("SOUTH", cell.getGridSquare(square.getX(), square.getY() + 1, square.getZ()));
                this.dumpTile("WEST", cell.getGridSquare(square.getX() - 1, square.getY(), square.getZ()));
                this.dumpTile("EAST", cell.getGridSquare(square.getX() + 1, square.getY(), square.getZ()));
            }
        }
        ImGui.sameLine();
        if (ImGui.beginPopup("room-temp")) {
            if (ImGui.inputFloat("Temperature", roomTempInput, 0, 0,"%.2f", ImGuiInputTextFlags.EnterReturnsTrue)) {
                debugInfo.runAction(RoomThermalDebugPacket.ACTION_SET_TEMP, roomTempInput.get());
            }
            ImGui.endPopup();
        }
        if (ImGui.button("Set room temperature")) {
            roomTempInput.set(room.getCurrentTemperature());
            ImGui.openPopup("room-temp");
        }
    }

    private void renderWorldOverlay(IsoThermalRoom room) {
        highlightRoomBounds(room);
        IsoCell cell = IsoWorld.instance.getCell();
        for (IsoThermalRoom.DebugInfo.Opening opening : room.getDebugInfo().getOpenings()) {
            IsoGridSquare sq = cell.getGridSquare(opening.x(), opening.y(), room.getZ());
            if (sq == null) continue;
            float[] anchor = this.edgeAnchor(opening.x(), opening.y(), opening.north());
            switch (opening.type()) {
                case "Window" -> {
                    this.highlightObject(sq.getWindow(opening.north()), 0.2f, 0.9f, 0.9f, 0.8f);
                    drawWorldLabel(anchor[0], anchor[1], room.getZ(), 0.5f, String.format("%.2f (W: %s | C: %s)", opening.coefficient(), opening.state(), opening.curtains()));
                }
                case "Door" -> {
                    this.highlightObject(sq.getDoor(opening.north()), 1.0f, 0.6f, 0.1f, 0.8f);
                    drawWorldLabel(anchor[0], anchor[1], room.getZ(), 0.5f, String.format("%.2f (%s)", opening.coefficient(), opening.state()));
                }
                default -> {
                    IsoObject edgeObject = this.findEdgeObject(sq, opening.north());
                    if (edgeObject != null) {
                        this.highlightObject(edgeObject, 1.0f, 0.1f, 0.1f, 0.8f);
                    } else {
                        this.highlightFloor(sq, 1.0f, 0.1f, 0.1f, 0.8f);
                    }
                }
            }
        }
        for (IsoThermalRoom.DebugInfo.Stair stair : room.getDebugInfo().getStairs()) {
            this.highlightFloor(cell.getGridSquare(stair.bottomX(), stair.bottomY(), stair.bottomZ()), 0.8f, 0.2f, 0.8f, 0.8f);
            this.highlightFloor(cell.getGridSquare(stair.topX(), stair.topY(), stair.topZ()), 0.8f, 0.2f, 0.8f, 0.8f);
            String text = stair.otherRoomId() != -1 ? String.format("Room %d:\n%.1f °C", stair.otherRoomId(), stair.otherTemp()) : "?";
            drawWorldLabel(stair.bottomX() + 0.5f, stair.bottomY() + 0.5f, stair.bottomZ(), 0.3f, text);
        }
        for (IsoThermalRoom.DebugInfo.HeatSource heatSource : room.getDebugInfo().getHeatSources()) {
            IsoGridSquare sq = cell.getGridSquare(heatSource.x(), heatSource.y(), heatSource.z());
            IsoObject sourceObject = this.findHeatSourceObject(sq);
            if (sourceObject != null) {
                this.highlightObject(sourceObject, 1.0f, 0.3f, 0.0f, 0.8f);
            } else {
                this.highlightFloor(sq, 1.0f, 0.3f, 0.0f, 0.8f);
            }
            drawWorldLabel(heatSource.x() + 0.5f, heatSource.y() + 0.5f, heatSource.z(), 0.2f, String.format("%d °C x%.2f", heatSource.temperature(), heatSource.coefficient()));
        }
    }

    // Windows/doors sit on one edge of the tile (north edge or west edge), not the tile's center -
    // anchoring there instead of the centroid removes the facing-dependent skew.
    private float[] edgeAnchor(int x, int y, boolean north) {
        return north ? new float[]{x + 0.5f, y} : new float[]{x, y + 0.5f};
    }

    static void highlightRoomBounds(IsoThermalRoom room, float r, float g, float b, float a, boolean labels) {
        IsoThermalRoom.DebugInfo debugInfo = room.getDebugInfo();
        if (debugInfo == null) return;
        highlightRoomBounds(debugInfo.getTiles(), room.getZ(), r, g, b, a, labels ? roomLabel(room) : null);
    }

    static void highlightRoomBounds(Set<Long> tiles, int z, float r, float g, float b, float a, @Nullable String label) {
        if (tiles == null || tiles.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        long sumX = 0, sumY = 0;
        for (long tile : tiles) {
            int tileX = IsoThermalRoom.unpackX(tile), tileY = IsoThermalRoom.unpackY(tile);
            minX = Math.min(minX, tileX);
            minY = Math.min(minY, tileY);
            maxX = Math.max(maxX, tileX);
            maxY = Math.max(maxY, tileY);
            sumX += tileX;
            sumY += tileY;
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        boolean[][] grid = new boolean[height][width];
        for (long tile : tiles) {
            int tileX = IsoThermalRoom.unpackX(tile), tileY = IsoThermalRoom.unpackY(tile);
            grid[tileY - minY][tileX - minX] = true;
        }

        // Repeatedly carve out the single largest all-room rectangle remaining in the grid
        // ("largest rectangle in a binary matrix") instead of merging row by row - this collapses
        // a shape like an L into exactly as many rectangles as its concave corners require.
        int[] colHeights = new int[width];
        while (true) {
            Arrays.fill(colHeights, 0);
            int bestArea = 0, bestRowStart = 0, bestRowEnd = 0, bestColStart = 0, bestColEnd = 0;
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    colHeights[col] = grid[row][col] ? colHeights[col] + 1 : 0;
                }
                int[] best = largestRectangleInHistogram(colHeights);
                if (best[0] > bestArea) {
                    bestArea = best[0];
                    bestColStart = best[1];
                    bestColEnd = best[2];
                    bestRowEnd = row + 1;
                    bestRowStart = bestRowEnd - best[3];
                }
            }
            if (bestArea == 0) break;
            for (int row = bestRowStart; row < bestRowEnd; row++) {
                for (int col = bestColStart; col < bestColEnd; col++) {
                    grid[row][col] = false;
                }
            }
            FBORenderAreaHighlights.getInstance()
                    .addHighlight(minX + bestColStart, minY + bestRowStart, minX + bestColEnd, minY + bestRowEnd, z, r, g, b, a);
        }

        if (label != null) {
            float centroidX = (float) sumX / tiles.size() + 0.5f;
            float centroidY = (float) sumY / tiles.size() + 0.5f;
            drawWorldLabel(centroidX, centroidY, z, 0.0f, label);
        }
    }

    static void highlightRoomBounds(IsoThermalRoom room, float r, float g, float b, float a) {
        highlightRoomBounds(room, r, g, b, a, true);
    }

    static void highlightRoomBounds(IsoThermalRoom room) {
        highlightRoomBounds(room, 1.0f, 1.0f, 1.0f, 0.25f);
    }

    private static String roomLabel(IsoThermalRoom room) {
        return String.format("Room %d:\n%.2f °C -> %.2f °C", room.getId(), room.getCurrentTemperature(), room.getTargetTemperature());
    }

    // Standard "largest rectangle in histogram" stack scan, extended to also return the winning
    // column range and height so the caller can reconstruct which rows the rectangle spans.
    private static int[] largestRectangleInHistogram(int[] heights) {
        int n = heights.length;
        Deque<Integer> stack = new ArrayDeque<>();
        int bestArea = 0, bestLeft = 0, bestRight = 0, bestHeight = 0;
        for (int i = 0; i <= n; i++) {
            int h = i == n ? 0 : heights[i];
            while (!stack.isEmpty() && heights[stack.peek()] >= h) {
                int poppedHeight = heights[stack.pop()];
                int left = stack.isEmpty() ? 0 : stack.peek() + 1;
                int area = poppedHeight * (i - left);
                if (area > bestArea) {
                    bestArea = area;
                    bestLeft = left;
                    bestRight = i;
                    bestHeight = poppedHeight;
                }
            }
            stack.push(i);
        }
        return new int[]{bestArea, bestLeft, bestRight, bestHeight};
    }

    private static void drawWorldLabel(float x, float y, float z, float heightOffset, String text) {
        PlayerCamera camera = IsoCamera.cameras[IsoCamera.frameState.playerIndex];
        float sx = IsoUtils.XToScreen(x, y, z, 0);
        float sy = IsoUtils.YToScreen(x, y, z + heightOffset, 0);
        sx -= camera.getOffX();
        sy -= camera.getOffY();
        sx /= camera.zoom;
        sy /= camera.zoom;
        sy -= TextManager.instance.getFontHeight(UIFont.Small) / 2.0f;
        TextManager.instance.DrawStringCentreDefered(UIFont.Small, sx + 1, sy + 1, text, 0.0, 0.0, 0.0, 1.0); // shadow for readability
        TextManager.instance.DrawStringCentreDefered(UIFont.Small, sx, sy, text, 1.0, 1.0, 1.0, 1.0);
    }

    private IsoObject findHeatSourceObject(IsoGridSquare sq) {
        if (sq == null) return null;
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject obj = sq.getObjects().get(i);
            if (obj instanceof IsoFireplace || obj instanceof IsoFire || obj instanceof IsoBarbecue) {
                return obj;
            }
        }
        return null;
    }

    /** The object forming the given edge of the square (the one whose own sprite collides on it), if any. */
    private IsoObject findEdgeObject(IsoGridSquare sq, boolean north) {
        if (sq == null) return null;
        IsoFlagType collide = north ? IsoFlagType.collideN : IsoFlagType.collideW;
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject obj = sq.getObjects().get(i);
            if (obj.getProperties() != null && obj.getProperties().has(collide)) return obj;
        }
        return null;
    }

    private void highlightFloor(IsoGridSquare sq, float r, float g, float b, float a) {
        if (sq == null) return;
        this.highlightObject(sq.getFloor(), r, g, b, a);
    }

    private void highlightObject(IsoObject obj, float r, float g, float b, float a) {
        if (obj == null) return;
        obj.setHighlighted(true, true);
        obj.setHighlightColor(r, g, b, a);
    }

    private void dumpTile(String label, IsoGridSquare square) {
        if (square == null) {
            DebugType.General.println(label + ": null");
            return;
        }
        square.RecalcProperties(); // force fresh - getProperties() alone can reflect a stale/incomplete computation
        StringBuilder builder = new StringBuilder();
        builder.append(label).append(" (").append(square.getX()).append(",").append(square.getY()).append(",").append(square.getZ()).append(")");
        builder.append(" squareFlags=").append(square.getProperties().getFlagsList());
        for (int i = 0; i < square.getObjects().size(); i++) {
            IsoObject obj = square.getObjects().get(i);
            builder.append(" | ").append(obj.getClass().getSimpleName());
            builder.append("/type=").append(obj.getType());
            builder.append("/sprite=").append(obj.getSpriteName());
            if (obj.getProperties() != null) {
                builder.append("/flags=").append(obj.getProperties().getFlagsList());
            }
        }
        DebugType.General.println(builder.toString());
    }

    private void renderTempHistoryGraph(float[] history, int historySize, int writeIndex, float r, float g, float b) {
        if (historySize == 0) {
            ImGui.textDisabled("Not enough data yet.");
            return;
        }

        // Only show the most recent 10 samples (a sliding window) instead of the whole accumulated
        // history, so the x-axis stays a fixed size instead of growing as more samples come in.
        int displayCount = Math.min(historySize, 10);
        int skip = historySize - displayCount;
        int ringOffset = historySize < history.length ? 0 : writeIndex;
        float minTemp = Float.MAX_VALUE, maxTemp = -Float.MAX_VALUE;
        double[] xs = new double[displayCount];
        double[] ys = new double[displayCount];
        for (int i = 0; i < displayCount; i++) {
            float value = history[(ringOffset + skip + i) % history.length];
            xs[i] = i;
            ys[i] = value;
            minTemp = Math.min(minTemp, value);
            maxTemp = Math.max(maxTemp, value);
        }

        ImPlot.pushStyleVar(ImPlotStyleVar.LabelPadding, new ImVec2(2, 2));
        ImPlot.setNextPlotLimits(0, Math.max(displayCount - 1, 1), minTemp - 1.0, maxTemp + 1.0, 1);
        if (ImPlot.beginPlot("##tempHistory", "", "", new ImVec2(0, 150),
                ImPlotFlags.NoTitle | ImPlotFlags.NoLegend | ImPlotFlags.NoMenus | ImPlotFlags.NoBoxSelect,
                ImPlotAxisFlags.NoDecorations, ImPlotAxisFlags.None)) {
            this.pushPlotColor(ImPlotCol.Line, r, g, b, 1.0f);
            ImPlot.plotLine("##line", xs, ys, displayCount, 0);
            ImPlot.popStyleColor();
            ImPlot.endPlot();
        }
        ImPlot.popStyleVar();
    }

    private void pushPlotColor(int plotCol, float r, float g, float b, float a) {
        ImPlot.pushStyleColor(plotCol, new ImVec4(g, b, a, r));
    }

    private void renderWeightContributionsTooltip(IsoThermalRoom.DebugInfo debugInfo) {
        float[] contributions = debugInfo.getWeightContributions();

        float maxContribution = 0.001f;
        for (float c : contributions) maxContribution = Math.max(maxContribution, c);
        float labelX = maxContribution * 1.4f;

        ImGui.beginTooltip();

        ImPlot.setNextPlotLimits(0.001, labelX * 6, -1, CONTRIBUTION_LABELS.length, 1);
        if (ImPlot.beginPlot("##weightContributions", "", "", new ImVec2(460, 220),
                ImPlotFlags.NoTitle | ImPlotFlags.NoMenus | ImPlotFlags.NoBoxSelect | ImPlotFlags.NoMousePos,
                ImPlotAxisFlags.LogScale | ImPlotAxisFlags.NoDecorations, ImPlotAxisFlags.NoDecorations)) {
            ImPlot.setLegendLocation(ImPlotLocation.East, ImPlotOrientation.Vertical, true);
            for (int i = 0; i < contributions.length; i++) {
                Float[] xs = {contributions[i]};
                Float[] ys = {(float) i};
                float[] color = CONTRIBUTION_COLORS[i];
                this.pushPlotColor(ImPlotCol.Fill, color[0], color[1], color[2], 1.0f);
                ImPlot.plotBarsH(CONTRIBUTION_LABELS[i], xs, ys, 0.8f);
                ImPlot.popStyleColor();
                ImPlot.plotText(CONTRIBUTION_VALUE_FORMAT.format(contributions[i]), contributions[i] * 3, i);
            }
            ImPlot.endPlot();
        }
        ImGui.endTooltip();
    }

    public Long getSelectedRoomId() {
        return this.selectedRoomId;
    }

    public void open() {
        this.open.set(true);
    }
}
