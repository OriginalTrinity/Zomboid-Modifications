package zombie.characters.animals;

import zombie.GameTime;
import zombie.core.logger.ExceptionLogger;
import zombie.core.random.Rand;
import zombie.debug.DebugType;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoWorld;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.iso.areas.PastureRegrowth;
import zombie.iso.objects.IsoFeedingTrough;
import zombie.iso.objects.IsoHutch;
import zombie.network.GameClient;
import zombie.util.PZCalendar;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;

public class AnimalCatchUp {
    private static final long ENCLOSURE_WAIT = 10000L;
    private static final Comparator<IsoAnimal> ADULTS_FIRST = Comparator.comparing(IsoAnimal::isBaby);
    private static final ArrayList<IsoAnimal> pending = new ArrayList<>();
    private static final ArrayList<IsoGridSquare> grass = new ArrayList<>();
    private static boolean running;
    private static boolean puddles;
    private static int grassEaten;

    private AnimalCatchUp() {}

    // Test log for the animal changes (catch-up, trough water, regrowth, home pasture, hutch dirt): one line per event,
    // "[Animals] <event> key=value ...", formatted with Locale.ROOT so it can be parsed from console.txt.
    public static void log(String format, Object... params) {
        DebugType.General.println("[Animals] " + String.format(Locale.ROOT, format, params));
    }

    public static void add(IsoAnimal animal) {
        // wild animals and animals outside zones get no catch-up (as vanilla), so don't keep them frozen
        if (animal.isWild() || DesignationZoneAnimal.getZoneF(animal.getX(), animal.getY(), animal.getZ()) == null) {
            log("release id=%d type=%s reason=%s", animal.getAnimalID(), animal.getAnimalType(), animal.isWild() ? "wild" : "nozone");
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
        return running ? puddles : IsoPuddles.getInstance().getPuddlesSize() > 0.13F;
    }

    public static IsoGridSquare pollGrass() {
        while (!grass.isEmpty()) {
            int index = Rand.Next(grass.size());
            IsoGridSquare square = grass.get(index);
            grass.set(index, grass.getLast());
            grass.removeLast();
            if (square.checkHaveGrass()) {
                grassEaten++;
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
                log("dropped id=%d type=%s clock=%d", animal.getAnimalID(), animal.getAnimalType(), animal.timeSinceLastUpdate);
                pending.remove(i);
                animal.pendingCatchUp = false;
                animal.fromMeta = false;
                continue;
            }

            DesignationZoneAnimal zone = DesignationZoneAnimal.getZoneF(animal.getX(), animal.getY(), animal.getZ());
            if (zone == null) {
                log("release id=%d type=%s reason=nozone", animal.getAnimalID(), animal.getAnimalType());
                pending.remove(i);
                release(animal);
                continue;
            }

            if (animal.pendingSince == 0L) {
                animal.pendingSince = now;
            }

            ArrayList<DesignationZoneAnimal> enclosure = DesignationZoneAnimal.getAllDZones(null, zone, null);
            boolean loaded = isLoaded(enclosure);
            if (!loaded && now - animal.pendingSince < ENCLOSURE_WAIT) continue;

            try {
                run(enclosure, takePending(enclosure), now - animal.pendingSince, !loaded);
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

    private static void run(ArrayList<DesignationZoneAnimal> enclosure, ArrayList<IsoAnimal> animals, long waitedMs, boolean timedOut) {
        ArrayList<IsoAnimal> order = new ArrayList<>();
        ArrayList<IsoFeedingTrough> troughs = new ArrayList<>();
        boolean waterStarted = false;
        boolean completed = false;
        long startedMs = System.currentTimeMillis();
        int simulatedHours = 0;
        grassEaten = 0;

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
                    log("release id=%d type=%s reason=wild", animal.getAnimalID(), animal.getAnimalType());
                    release(animal);
                    continue;
                }

                animal.beginCatchUp();
                long clockBefore = animal.timeSinceLastUpdate;
                int hours = animal.getCatchUpHours(now);
                log("animal-begin enclosure=%.0f id=%d type=%s baby=%b hours=%d clock=%d survived=%.2f age=%d hunger=%.3f thirst=%.3f",
                    enclosure.getFirst().getId(), animal.getAnimalID(), animal.getAnimalType(), animal.isBaby(), hours,
                    clockBefore, animal.getHoursSurvived(), animal.getData().getAge(), animal.getHunger(), animal.getThirst());
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

            // overlapping zones list the same trough or hutch twice; CatchUpWater would lower such a trough twice
            for (DesignationZoneAnimal zone : enclosure) {
                for (IsoFeedingTrough trough : zone.getTroughs()) {
                    if (!troughs.contains(trough)) troughs.add(trough);
                }

                for (IsoHutch hutch : zone.getHutchs()) {
                    if (!hutches.contains(hutch)) hutches.add(hutch);
                }
            }

            // regrowth only for fully loaded zones: tick() advances a zone's clock but can only regrow loaded squares
            ArrayList<DesignationZoneAnimal> loadedZones = new ArrayList<>();
            for (DesignationZoneAnimal zone : enclosure) {
                if (zone.isAllChunksLoaded()) loadedZones.add(zone);
            }

            long start = now - maxHours * IsoAnimal.HOUR_MS;
            double worldAgeNow = GameTime.getInstance().getWorldAgeHours();
            simulatedHours = maxHours;
            log("run-start enclosure=%.0f zones=%d loadedZones=%d animals=%d troughs=%d hutches=%d maxHours=%d worldAge=%.2f waitedMs=%d timedOut=%b",
                enclosure.getFirst().getId(), enclosure.size(), loadedZones.size(), order.size(), troughs.size(), hutches.size(), maxHours,
                worldAgeNow, waitedMs, timedOut);
            PZCalendar hourCal = PZCalendar.getInstance();
            PZCalendar animalCal = PZCalendar.getInstance();
            running = true;
            collectGrass(enclosure);
            waterStarted = true;
            CatchUpWater.begin(troughs, start, now, worldAgeNow);

            for (int h = 0; h < maxHours; h++) {
                long hourStart = start + h * IsoAnimal.HOUR_MS;
                double worldAgeHour = worldAgeNow - (double) (now - hourStart) / IsoAnimal.HOUR_MS;
                hourCal.setTimeInMillis(hourStart);
                int hourOfDay = hourCal.get(Calendar.HOUR_OF_DAY);
                CatchUpWater.addRain(troughs, hourStart, worldAgeHour);
                puddles = CatchUpWater.hasPuddles(worldAgeHour);
                if (hourOfDay == 0) {
                    PastureRegrowth.tick(loadedZones, (int) worldAgeHour);
                    collectGrass(enclosure);
                }

                Collections.shuffle(order);
                order.sort(ADULTS_FIRST);
                HashMap<IsoHutch, Integer> hutchRoom = new HashMap<>();

                for (IsoAnimal animal : order) {
                    if (animal.isDead() || animal.timeSinceLastUpdate > hourStart) continue;

                    boolean sheltered = takeShelter(animal, hourOfDay, hutches, hutchRoom);
                    animal.catchUpHour(animalCal, sheltered);
                }
            }

            completed = true;
            PastureRegrowth.tick(loadedZones, (int) worldAgeNow);
        } finally {
            running = false;
            puddles = false;
            grass.clear();

            // pendingCatchUp is only still set after an exception (or for the animals in `order`): end them so the clock
            // moves again, and unfreeze the ones the exception came before
            for (IsoAnimal animal : animals) {
                if (animal.pendingCatchUp) {
                    animal.endCatchUp();
                    animal.fromMeta = false;
                }
            }

            if (waterStarted) {
                try {
                    CatchUpWater.end(troughs, !completed);
                } catch (Exception e) {
                    ExceptionLogger.logException(e);   // don't hide an exception from the catch-up itself
                }
            }

            for (IsoAnimal animal : order) {
                log("animal-end enclosure=%.0f id=%d type=%s clock=%d survived=%.2f age=%d hunger=%.3f thirst=%.3f health=%.3f dead=%b",
                    enclosure.getFirst().getId(), animal.getAnimalID(), animal.getAnimalType(), animal.timeSinceLastUpdate,
                    animal.getHoursSurvived(), animal.getData().getAge(), animal.getHunger(), animal.getThirst(), animal.getHealth(),
                    animal.isDead());
            }

            if (!order.isEmpty()) {
                log("run-end enclosure=%.0f hours=%d grassEaten=%d completed=%b ms=%d", enclosure.getFirst().getId(), simulatedHours,
                    grassEaten, completed, System.currentTimeMillis() - startedMs);
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
