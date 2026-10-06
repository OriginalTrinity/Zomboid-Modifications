package zombie.characters.animals;

import zombie.SandboxOptions;
import zombie.entity.components.fluids.Fluid;
import zombie.entity.components.fluids.FluidContainer;
import zombie.entity.components.fluids.FluidType;
import zombie.iso.areas.DesignationZoneAnimal;
import zombie.iso.objects.IsoFeedingTrough;
import zombie.iso.weather.ClimateForecaster;
import zombie.iso.weather.ClimateManager;
import zombie.iso.weather.WeatherPeriod;
import zombie.network.GameServer;

import java.util.ArrayList;
import java.util.HashMap;

public class CatchUpWater {
    private static final int PAST_DAYS = 10;
    private static final float PUDDLE_INTENSITY = 0.1f;
    private static final int PUDDLE_HOURS = 3;
    private static final HashMap<IsoFeedingTrough, TroughRain> rainFrom = new HashMap<>();

    private CatchUpWater() {}

    static void begin(ArrayList<IsoFeedingTrough> troughs, long start, long now, double worldAgeNow) {
        rainFrom.clear();

        for (IsoFeedingTrough trough : troughs) {
            FluidContainer fluidContainer = trough.getFluidContainer();
            if (fluidContainer == null || trough.getSquare() == null || !trough.getSquare().isOutside()) continue;
            float level = fluidContainer.getAmount();
            float capacity = fluidContainer.getCapacity();
            Double recordedLevel = trough.getRecordedWaterLevel();
            Double recordedHour = trough.getRecordedWaterHour();
            long from;
            float startLevel;
            float rain = 0.0f;

            if (recordedLevel != null && recordedHour != null) {
                // the real level at the last record before the trough unloaded
                from = Math.max(start, now - (long) ((worldAgeNow - recordedHour) * IsoAnimal.HOUR_MS));
                startLevel = Math.min(recordedLevel.floatValue(), capacity);
            } else {
                from = Math.max(start, unloadedSince(trough, now, worldAgeNow));   // no record: estimate as before
                startLevel = -1.0f;
            }

            for (long t = start; t < now; t += IsoAnimal.HOUR_MS) {
                if (t >= from) rain += rainForHour(fluidContainer, worldAgeNow - (double) (now - t) / IsoAnimal.HOUR_MS);
            }

            if (startLevel < 0.0f) {
                startLevel = level < capacity ? Math.max(0.0f, level - rain) : Math.max(0.0f, capacity - rain);
            }

            fluidContainer.adjustAmount(startLevel);
            float caught = Math.max(0.0f, level - startLevel);
            float budget = level < capacity ? caught : Math.max(caught, rain);
            rainFrom.put(trough, new TroughRain(from, budget));
        }
    }

    static void addRain(ArrayList<IsoFeedingTrough> troughs, long hourStart, double worldAgeHours) {
        for (IsoFeedingTrough trough : troughs) {
            TroughRain troughRain = rainFrom.get(trough);
            FluidContainer fluidContainer = trough.getFluidContainer();
            if (troughRain == null || fluidContainer == null || hourStart < troughRain.from) continue;
            float amount = Math.min(Math.min(rainForHour(fluidContainer, worldAgeHours), fluidContainer.getFreeCapacity()), troughRain.budget);
            FluidType type = fluidContainer.isFilledWithCleanWater() ? FluidType.Water : FluidType.TaintedWater;
            if (amount > 0.0f && fluidContainer.canAddFluid(Fluid.Get(type))) {
                fluidContainer.addFluid(type, amount);
                troughRain.budget -= amount;
            }
        }
    }

    static boolean hasPuddles(double worldAgeHours) {
        for (int i = 0; i < PUDDLE_HOURS; i++) {
            Precipitation precip = precipitationAt(worldAgeHours - i);
            if (!precip.snow() && precip.intensity() >= PUDDLE_INTENSITY) return true;
        }
        return false;
    }

    // aborted: an exception stopped the catch-up, give back the rain begin() took out and addRain didn't re-add yet
    static void end(ArrayList<IsoFeedingTrough> troughs, boolean aborted) {
        for (IsoFeedingTrough trough : troughs) {
            if (aborted) restoreRemaining(trough);
            trough.checkOverlayAfterAnimalEat();
            trough.writeWaterLevel();   // the next absence starts from the level after this catch-up
            trough.updateLuaObject();
            if (GameServer.server) trough.sync();
        }
        rainFrom.clear();
    }

    private static void restoreRemaining(IsoFeedingTrough trough) {
        TroughRain troughRain = rainFrom.get(trough);
        FluidContainer fluidContainer = trough.getFluidContainer();
        if (troughRain == null || fluidContainer == null || troughRain.budget <= 0.0f) return;
        float amount = Math.min(troughRain.budget, fluidContainer.getFreeCapacity());
        FluidType type = fluidContainer.isFilledWithCleanWater() ? FluidType.Water : FluidType.TaintedWater;
        if (amount > 0.0f && fluidContainer.canAddFluid(Fluid.Get(type))) {
            fluidContainer.addFluid(type, amount);
        }
    }

    private static long unloadedSince(IsoFeedingTrough trough, long now, double worldAgeNow) {
        DesignationZoneAnimal zone = DesignationZoneAnimal.getZoneF(trough.getX(), trough.getY(), trough.getZ());
        if (zone == null || zone.hourLastSeen <= 1) return Long.MIN_VALUE;
        return now - (long) ((worldAgeNow - zone.hourLastSeen) * IsoAnimal.HOUR_MS);
    }

    private static float rainForHour(FluidContainer fluidContainer, double worldAgeHours) {
        Precipitation precip = precipitationAt(worldAgeHours);
        if (precip.intensity() <= 0.0f) return 0.0f;
        float snowModifier = precip.snow() ? 0.5f : 1.0f;
        return 0.005f * precip.intensity() * snowModifier * fluidContainer.getRainCatcher() * 60.0f * SandboxOptions.getInstance().getDayLengthMinutes();
    }

    private static Precipitation precipitationAt(double worldAgeHours) {
        ClimateManager climate = ClimateManager.getInstance();
        ClimateForecaster forecaster = climate != null ? climate.getClimateForecaster() : null;
        if (forecaster == null) return Precipitation.NONE;

        for (int offset = -PAST_DAYS; offset <= 0; offset++) {
            ClimateForecaster.DayForecast day = forecaster.getForecast(offset);
            if (day == null || !day.isWeatherStarts() || day.getWeatherPeriod() == null) continue;
            WeatherPeriod period = day.getWeatherPeriod();
            WeatherPeriod.WeatherStage stage = period.getStageForWorldAge(worldAgeHours);
            if (stage != null) {
                float intensity = stageIntensity(stage.getStageID()) * (0.5f + 0.5f * period.getTotalStrength());
                boolean snow = day.isChanceOnSnow() || stage.getStageID() == WeatherPeriod.STAGE_BLIZZARD;
                return new Precipitation(intensity, snow);
            }
        }
        return Precipitation.NONE;
    }

    private static float stageIntensity(int stageId) {
        return switch (stageId) {
            case WeatherPeriod.STAGE_DRIZZLE, WeatherPeriod.STAGE_CLEARING -> 0.25f;
            case WeatherPeriod.STAGE_SHOWERS, WeatherPeriod.STAGE_MODERATE, WeatherPeriod.STAGE_MODDED -> 0.5f;
            case WeatherPeriod.STAGE_HEAVY_PRECIP, WeatherPeriod.STAGE_STORM, WeatherPeriod.STAGE_BLIZZARD -> 0.9f;
            case WeatherPeriod.STAGE_TROPICAL_STORM, WeatherPeriod.STAGE_KATEBOB_STORM -> 1.0f;
            default -> 0.0f;
        };
    }

    private static final class TroughRain {
        final long from;
        float budget;

        TroughRain(long from, float budget) {
            this.from = from;
            this.budget = budget;
        }
    }

    private record Precipitation(float intensity, boolean snow) {
        static final Precipitation NONE = new Precipitation(0.0f, false);
    }
}
