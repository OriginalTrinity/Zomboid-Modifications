package zombie.iso.weather.dbg;

import zombie.GameTime;
import zombie.iso.IsoThermalRoom;
import zombie.iso.weather.ClimateManager;
import zombie.iso.weather.ClimateValues;

import java.util.*;

public final class ThermalForecast {

    public static final int HORIZON_HOURS = 24;
    public static final float STEP_HOURS = 0.25f;
    public static final int STEPS = (int) (HORIZON_HOURS / STEP_HOURS);

    private static ClimateValues climateValues;

    public record OutdoorCurve(float[] temps, float[] sun) {}
    public record Result(double[] hours, double[] roomTemps, double[] outdoorTemps, int roomCount, float maxTemp, float maxHour, float minTemp, float minHour) {}

    private ThermalForecast() {}

    /** Hourly outdoor temperatures from now to now + {@code HORIZON_HOURS}, shifted so hour 0 matches the live temperature. */
    public static OutdoorCurve sampleOutdoorCurve() {
        if (climateValues == null) climateValues = ClimateManager.getInstance().getClimateValuesCopy();
        GameTime gt = GameTime.getInstance();
        GregorianCalendar calendar = new GregorianCalendar(gt.getYear(), gt.getMonth(), gt.getDayPlusOne(), gt.getHour(), gt.getMinutes());
        float[] curve = new float[HORIZON_HOURS + 1];
        float[] sun = new float[HORIZON_HOURS + 1];
        for (int h = 0; h <= HORIZON_HOURS; h++) {
            if (h > 0) calendar.add(Calendar.HOUR_OF_DAY, 1);
            climateValues.pollDate(calendar);
            curve[h] = climateValues.getTemperature();
            sun[h] = climateValues.getDayLightStrength() * (1.0f - climateValues.getCloudIntensity());
        }

        float offset = ClimateManager.getInstance().getTemperature() - curve[0];
        for (int h = 0; h < curve.length; h++) curve[h] += offset;
        return new OutdoorCurve(curve, sun);
    }

    public static Result run(IsoThermalRoom root, OutdoorCurve outdoor) {
        List<IsoThermalRoom> rooms = collectConnectedRooms(root);
        IdentityHashMap<IsoThermalRoom, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < rooms.size(); i++) index.put(rooms.get(i), i);

        int count = rooms.size();
        float[] temps = new float[count];
        float[] targets = new float[count];
        float[] weightSums = new float[count];
        for (int i = 0; i < count; i++) temps[i] = rooms.get(i).getCurrentTemperature();
        IsoThermalRoom.NeighborTemperature lookup = room -> {
            Integer i = index.get(room);
            return i != null ? temps[i] : room.getCurrentTemperature();
        };

        double[] hours = new double[STEPS + 1];
        double[] roomTemps = new double[STEPS + 1];
        double[] outdoorTemps = new double[STEPS + 1];
        roomTemps[0] = temps[0];
        outdoorTemps[0] = outdoor.temps()[0];
        float maxTemp = temps[0], minTemp = temps[0];
        float maxHour = 0.0f, minHour = 0.0f;

        for (int s = 1; s <= STEPS; s++) {
            float outside = sampleCurve(outdoor.temps(), (s - 1) * STEP_HOURS);
            float sun = sampleCurve(outdoor.sun(), (s - 1) * STEP_HOURS);

            for (int i = 0; i < count; i++) {
                float[] eval = rooms.get(i).evaluateTargetTemperature(outside, sun, lookup);
                targets[i] = eval[2];
                weightSums[i] = eval[0];
            }
            for (int i = 0; i < count; i++) {
                temps[i] += IsoThermalRoom.computeTempDelta(temps[i], targets[i], weightSums[i], rooms.get(i).getSquares().size(), STEP_HOURS);
            }

            float hour = s * STEP_HOURS;
            hours[s] = hour;
            roomTemps[s] = temps[0];
            outdoorTemps[s] = sampleCurve(outdoor.temps(), hour);
            if (temps[0] > maxTemp) {
                maxTemp = temps[0];
                maxHour = hour;
            }
            if (temps[0] < minTemp) {
                minTemp = temps[0];
                minHour = hour;
            }
        }
        return new Result(hours, roomTemps, outdoorTemps, count, maxTemp, maxHour, minTemp, minHour);
    }

    private static List<IsoThermalRoom> collectConnectedRooms(IsoThermalRoom root) {
        List<IsoThermalRoom> rooms = new ArrayList<>();
        Set<IsoThermalRoom> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<IsoThermalRoom> queue = new ArrayDeque<>();
        seen.add(root);
        queue.add(root);
        float outsideTemp = ClimateManager.getInstance().getTemperature();
        while (!queue.isEmpty()) {
            IsoThermalRoom room = queue.poll();
            rooms.add(room);
            room.evaluateTargetTemperature(outsideTemp, 0.0f, neighbor -> {
                if (neighbor.getSquares() != null && seen.add(neighbor)) queue.add(neighbor);
                return neighbor.getCurrentTemperature();
            });
        }
        return rooms;
    }

    private static float sampleCurve(float[] hourly, double hour) {
        int i = Math.min((int) hour, hourly.length - 2);
        float t = (float) (hour - i);
        return hourly[i] + (hourly[i + 1] - hourly[i]) * t;
    }

}
