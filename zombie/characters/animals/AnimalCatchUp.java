package zombie.characters.animals;

import zombie.GameTime;
import zombie.core.logger.ExceptionLogger;
import zombie.core.random.Rand;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoWorld;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.iso.objects.IsoHutch;
import zombie.network.GameClient;
import zombie.util.PZCalendar;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

public class AnimalCatchUp {
    private static final long ENCLOSURE_WAIT = 10000L;
    private static final Comparator<IsoAnimal> ADULTS_FIRST = Comparator.comparing(IsoAnimal::isBaby);
    private static final ArrayList<IsoAnimal> pending = new ArrayList<>();
    private static final ArrayList<IsoGridSquare> grass = new ArrayList<>();
    private static boolean running;

    private AnimalCatchUp() {}

    public static void add(IsoAnimal animal) {
        // wild animals and animals outside zones get no catch-up (as vanilla), so don't keep them frozen
        if (animal.isWild() || DesignationZoneAnimal.getZoneF(animal.getX(), animal.getY(), animal.getZ()) == null) {
            release(animal);
            return;
        }

        animal.fromMeta = true;   // AnimalManagerMain leaves it unset while the game loads
        animal.pendingCatchUp = true;
        animal.pendingSince = 0L;   // the wait starts at the first update(), not during loading
        if (!pending.contains(animal)) {
            pending.add(animal);
        }
    }

    public static void reset() {
        pending.clear();
        grass.clear();
        running = false;
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean hasPuddles() {
        return IsoPuddles.getInstance().getPuddlesSize() > 0.13F;
    }

    public static IsoGridSquare pollGrass() {
        while (!grass.isEmpty()) {
            int index = Rand.Next(grass.size());
            IsoGridSquare square = grass.get(index);
            grass.set(index, grass.getLast());
            grass.removeLast();
            if (square.checkHaveGrass()) {
                return square;
            }
        }
        return null;
    }

    public static boolean hasPendingIn(DesignationZoneAnimal zone) {
        if (pending.isEmpty()) return false;

        ArrayList<DesignationZoneAnimal> enclosure = DesignationZoneAnimal.getAllDZones(null, zone, null);

        for (int i = 0; i < pending.size(); i++) {
            IsoAnimal animal = pending.get(i);
            DesignationZoneAnimal animalZone = DesignationZoneAnimal.getZoneF(animal.getX(), animal.getY(), animal.getZ());
            if (animalZone != null && enclosure.contains(animalZone)) return true;
        }

        return false;
    }

    public static void update() {
        if (GameClient.client || pending.isEmpty() || IsoWorld.instance.currentCell == null) {
            return;
        }

        long now = System.currentTimeMillis();

        for (int i = pending.size() - 1; i >= 0; i--) {
            if (i >= pending.size()) continue;

            IsoAnimal animal = pending.get(i);
            if (!animal.pendingCatchUp || !animal.isExistInTheWorld()) {
                pending.remove(i);
                animal.pendingCatchUp = false;
                animal.fromMeta = false;
                continue;
            }

            DesignationZoneAnimal zone = DesignationZoneAnimal.getZoneF(animal.getX(), animal.getY(), animal.getZ());
            if (zone == null) {
                pending.remove(i);
                release(animal);
                continue;
            }

            if (animal.pendingSince == 0L) {
                animal.pendingSince = now;
            }

            ArrayList<DesignationZoneAnimal> enclosure = DesignationZoneAnimal.getAllDZones(null, zone, null);
            if (!isLoaded(enclosure) && now - animal.pendingSince < ENCLOSURE_WAIT) continue;

            try {
                run(enclosure, takePending(enclosure));
            } catch (Exception e) {
                ExceptionLogger.logException(e);   // the other enclosures still get their catch-up
            }
        }
    }

    private static void release(IsoAnimal animal) {
        animal.pendingCatchUp = false;
        animal.fromMeta = false;
        animal.timeSinceLastUpdate = GameTime.getInstance().getCalender().getTimeInMillis();
    }

    private static boolean isLoaded(ArrayList<DesignationZoneAnimal> enclosure) {
        for (int i = 0; i < enclosure.size(); i++) {
            if (!enclosure.get(i).isAllChunksLoaded()) return false;
        }
        return true;
    }

    private static ArrayList<IsoAnimal> takePending(ArrayList<DesignationZoneAnimal> enclosure) {
        ArrayList<IsoAnimal> animals = new ArrayList<>();

        for (int i = pending.size() - 1; i >= 0; i--) {
            IsoAnimal animal = pending.get(i);
            DesignationZoneAnimal zone = DesignationZoneAnimal.getZoneF(animal.getX(), animal.getY(), animal.getZ());
            if (zone != null && enclosure.contains(zone) && animal.isExistInTheWorld()) {
                pending.remove(i);
                animals.add(animal);
            }
        }

        return animals;
    }

    private static void run(ArrayList<DesignationZoneAnimal> enclosure, ArrayList<IsoAnimal> animals) {
        ArrayList<IsoAnimal> order = new ArrayList<>();

        // the animals are out of `pending` already: whatever throws below, the finally must end or release them
        try {
            long now = GameTime.getInstance().getCalender().getTimeInMillis();
            ArrayList<IsoAnimal> everyone = new ArrayList<>();

            for (DesignationZoneAnimal zone : enclosure) {
                zone.rebuild();
                everyone.addAll(zone.getAnimals());
            }

            int maxHours = 0;

            for (IsoAnimal animal : animals) {
                if (animal.isWild()) {
                    release(animal);
                    continue;
                }

                animal.beginCatchUp();
                int hours = animal.getCatchUpHours(now);
                if (hours <= 0) {
                    animal.endCatchUp();
                    continue;
                }

                order.add(animal);
                maxHours = Math.max(maxHours, hours);
            }

            if (order.isEmpty()) {
                return;
            }

            for (IsoAnimal animal : order) {
                if (animal.isBaby()) {
                    animal.relinkMother(everyone);
                }
            }

            ArrayList<IsoHutch> hutches = new ArrayList<>();

            // overlapping zones list the same hutch twice
            for (DesignationZoneAnimal zone : enclosure) {
                for (IsoHutch hutch : zone.getHutchs()) {
                    if (!hutches.contains(hutch)) hutches.add(hutch);
                }
            }

            long start = now - maxHours * IsoAnimal.HOUR_MS;
            double worldAgeNow = GameTime.getInstance().getWorldAgeHours();
            PZCalendar hourCal = PZCalendar.getInstance();
            PZCalendar animalCal = PZCalendar.getInstance();
            running = true;
            collectGrass(enclosure);

            for (int h = 0; h < maxHours; h++) {
                long hourStart = start + h * IsoAnimal.HOUR_MS;
                double worldAgeHour = worldAgeNow - (double) (now - hourStart) / IsoAnimal.HOUR_MS;
                hourCal.setTimeInMillis(hourStart);
                int hourOfDay = hourCal.get(Calendar.HOUR_OF_DAY);
                Collections.shuffle(order);
                order.sort(ADULTS_FIRST);
                HashMap<IsoHutch, Integer> hutchRoom = new HashMap<>();

                for (IsoAnimal animal : order) {
                    if (animal.isDead() || animal.timeSinceLastUpdate > hourStart) continue;

                    boolean sheltered = takeShelter(animal, hourOfDay, hutches, hutchRoom);
                    animal.catchUpHour(animalCal, sheltered);
                }
            }

        } finally {
            running = false;
            grass.clear();

            // pendingCatchUp is only still set after an exception (or for the animals in `order`): end them so the clock
            // moves again, and unfreeze the ones the exception came before
            for (IsoAnimal animal : animals) {
                if (animal.pendingCatchUp) {
                    animal.endCatchUp();
                    animal.fromMeta = false;
                }
            }
        }

        for (IsoAnimal animal : order) {
            if (!animal.isDead()) {
                animal.forceWanderNow();
            }
        }
    }

    private static void collectGrass(ArrayList<DesignationZoneAnimal> enclosure) {
        grass.clear();
        IsoCell cell = IsoWorld.instance.getCell();

        for (DesignationZoneAnimal zone : enclosure) {
            for (int x = zone.getX(); x < zone.getX() + zone.getW(); x++) {
                for (int y = zone.getY(); y < zone.getY() + zone.getH(); y++) {
                    IsoGridSquare square = cell.getGridSquare(x, y, zone.getZ());
                    if (square != null && square.checkHaveGrass()) grass.add(square);
                }
            }
        }
    }

    private static boolean takeShelter(IsoAnimal animal, int hourOfDay, ArrayList<IsoHutch> hutches, HashMap<IsoHutch, Integer> taken) {
        if (animal.adef.enterHutchTime == 0 && animal.adef.exitHutchTime == 0 || !animal.adef.isInsideHutchTime(hourOfDay)) return false;

        for (IsoHutch hutch : hutches) {
            int used = taken.getOrDefault(hutch, 0);
            if (hutch.isDoorClosed() && animal.canBePutInHutch(hutch) && hutch.getAnimalInside().size() + used < hutch.getMaxAnimals()) {
                taken.put(hutch, used + 1);
                return true;
            }
        }
        return false;
    }
}
