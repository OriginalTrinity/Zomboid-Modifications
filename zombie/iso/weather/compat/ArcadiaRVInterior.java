package zombie.iso.weather.compat;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

import java.util.ArrayList;

public class ArcadiaRVInterior {
    private static int[][] regions; // {minX, maxX, minY, maxY}; null = not read yet

    public static boolean isInterior(int x, int y) {
        if (regions == null) {
            int[][] read = readRegions();
            if (read == null) return false; // mod not (yet) loaded, try again next time
            regions = read;
        }
        for (int[] r : regions) {
            if (x >= r[0] && x <= r[1] && y >= r[2] && y <= r[3]) return true;
        }
        return false;
    }

    public static void reset() {
        regions = null;
    }

    /** Null while the mod's RVInterior table doesn't exist. */
    private static int[][] readRegions() {
        if (LuaManager.env == null || !(LuaManager.env.rawget("RVInterior") instanceof KahluaTable rv)) {
            return null;
        }
        if (!(rv.rawget("RESERVED_MAP_REGIONS") instanceof KahluaTable list)) {
            return null;
        }
        ArrayList<int[]> out = new ArrayList<>();
        for (int i = 1; i <= list.len(); i++) {
            if (!(list.rawget(i) instanceof KahluaTable t)) continue;
            if (!(t.rawget("minX") instanceof Double minX) || !(t.rawget("maxX") instanceof Double maxX)
                    || !(t.rawget("minY") instanceof Double minY) || !(t.rawget("maxY") instanceof Double maxY)) continue;
            out.add(new int[]{minX.intValue(), maxX.intValue(), minY.intValue(), maxY.intValue()});
        }
        return out.toArray(int[][]::new);
    }
}
