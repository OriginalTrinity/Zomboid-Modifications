package zombie.iso.areas;

import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.characters.animals.AnimalCatchUp;
import zombie.core.properties.IsoPropertyType;
import zombie.core.random.Rand;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

import java.util.ArrayList;
import java.util.List;

public class PastureRegrowth {
    private static final String DIRT_OVERLAY = "blends_natural_01_87";
    private static final String FARM_PLOT_PREFIX = "vegetation_farming";
    private static int lastHour = -1;

    private PastureRegrowth() {}

    public static void reset() {
        lastHour = -1;
    }

    public static void update() {
        int hour = (int) GameTime.getInstance().getWorldAgeHours();
        if (hour == lastHour || IsoWorld.instance.currentCell == null) {
            return;
        }

        lastHour = hour;
        ArrayList<DesignationZoneAnimal> zones = DesignationZoneAnimal.getAllZones();

        for (DesignationZoneAnimal zone : zones) {
            if (zone.isAllChunksLoaded() && !AnimalCatchUp.hasPendingIn(zone)) {
                tick(zone, hour);
            }
        }
    }

    public static void tick(List<? extends DesignationZone> zones, int untilHour) {
        for (DesignationZone zone : zones) {
            tick(zone, untilHour);
        }
    }

    public static void tick(DesignationZone zone, int untilHour) {
        if (zone.hourLastSeen <= 1) {
            zone.hourLastSeen = untilHour;
            return;
        }

        int hours = untilHour - zone.hourLastSeen;
        if (hours < 1) return;

        zone.hourLastSeen = untilHour;
        int regrowthHours = Math.max(1, SandboxOptions.instance.animalGrassRegrowTime.getValue());
        float chance = (float) (1.0 - Math.pow(1.0 - 1.0 / regrowthHours, hours));
        IsoCell cell = IsoWorld.instance.getCell();

        for (int x = zone.x; x < zone.x + zone.w; x++) {
            for (int y = zone.y; y < zone.y + zone.h; y++) {
                IsoGridSquare sq = cell.getGridSquare(x, y, zone.z);
                if (sq != null && Rand.Next(0.0f, 1.0f) < chance) {
                    regrow(sq);
                }
            }
        }

    }

    private static void regrow(IsoGridSquare square) {
        IsoObject floor = square.getFloor();
        if (floor == null || floor.getAttachedAnimSprite() == null || !floor.getProperties().has(IsoPropertyType.GRASS_FLOOR) || isFarmPlot(square)) {
            return;
        }

        for (int i = 0; i < floor.getAttachedAnimSprite().size(); i++) {
            if (DIRT_OVERLAY.equals(floor.getAttachedAnimSprite().get(i).parentSprite.getName())) {
                floor.RemoveAttachedAnim(i);
                if (GameServer.server) {
                    floor.transmitUpdatedSpriteToClients();
                }
                return;
            }
        }
    }

    private static boolean isFarmPlot(IsoGridSquare square) {
        for (int i = 0; i < square.getObjects().size(); i++) {
            IsoObject object = square.getObjects().get(i);
            if (object.getSprite() != null && object.getSprite().getName() != null && object.getSprite().getName().startsWith(FARM_PLOT_PREFIX)) {
                return true;
            }
        }
        return false;
    }
}
