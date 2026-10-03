package zombie.iso.weather;

import zombie.characters.IsoPlayer;
import zombie.iso.*;
import zombie.iso.objects.IsoLightSwitch;
import zombie.iso.weather.compat.ArcadiaRVInterior;
import zombie.network.GameClient;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.util.list.PZArrayList;

import java.util.Optional;

public class Thermostat {
    public static final float MIN = 15.0f, MAX = 28.0f, STEP = 0.5f;
    /** The default instances are immutable. Attempting to modify their values will throw {@code UnsupportedOperationException} */
    public static final Thermostat DEFAULT = new ImmutableThermostat(true, 22.0f);
    /** RVs start with climate control off, so their power bank isn't drained unless someone turns it on */
    public static final Thermostat DEFAULT_RV_INTERIOR = new ImmutableThermostat(false, 22.0f);

    public static final int API_VERSION = 1;
    public static final float MAX_DISTANCE = 3.0f;

    private boolean isOn;
    private float setPoint;

    public Thermostat(boolean isOn, float setPoint) {
        this.isOn = isOn;
        this.setPoint = setPoint;
    }

    public void clamp() {
        this.setPoint = clamp(this.setPoint);
    }

    /** Rounds to {@link #STEP} and clamps to {@link #MIN}..{@link #MAX}. */
    public static float clamp(float setPoint) {
        return Math.clamp(Math.round(setPoint / STEP) * STEP, MIN, MAX);
    }

    public boolean isOn() {
        return this.isOn;
    }

    public void setOn(boolean isOn) {
        this.isOn = isOn;
    }

    public float getSetPoint() {
        return this.setPoint;
    }

    public void setSetPoint(float setPoint) {
        this.setPoint = setPoint;
    }

    public boolean equalsState(boolean isOn, float setPoint) {
        return this.isOn == isOn && this.setPoint == setPoint;
    }

    public boolean isDefaultFor(long key) {
        Thermostat defaultThermostat = defaultFor(key);
        return this.equalsState(defaultThermostat.isOn, defaultThermostat.setPoint);
    }

    public static long key(BuildingDef def) {
        return IsoThermalRoom.packCoordinates(def.getX(), def.getY(), def.getMinLevel());
    }

    /** The state of a building whose thermostat was never changed. */
    public static Thermostat defaultFor(long key) {
        return ArcadiaRVInterior.isInterior(IsoThermalRoom.unpackX(key), IsoThermalRoom.unpackY(key)) ? DEFAULT_RV_INTERIOR : DEFAULT;
    }

    public static int getApiVersion() {
        return API_VERSION;
    }

    public static float getMinSetPoint() {
        return MIN;
    }

    public static float getMaxSetPoint() {
        return MAX;
    }

    public static float getSetPointStep() {
        return STEP;
    }

    public static float getMaxDistance() {
        return MAX_DISTANCE;
    }

    public static boolean isThermostat(IsoObject obj) {
        return obj instanceof IsoLightSwitch lightSwitch && !lightSwitch.getCanBeModified() && getRoomDef(lightSwitch) != null;
    }

    static RoomDef getRoomDef(IsoLightSwitch lightSwitch) {
        if (lightSwitch.roomId == -1L) return null;
        RoomDef def = IsoWorld.instance.getMetaGrid().getRoomDefByID(lightSwitch.roomId);
        return def != null  && !def.isUserDefined() && def.getBuilding() != null ? def : null;
    }

    static IsoLightSwitch findThermostatSwitch(IsoGridSquare sq) {
        if (sq == null) return null;
        PZArrayList<IsoObject> objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            if (isThermostat(objects.get(i))) return (IsoLightSwitch) objects.get(i);
        }
        return null;
    }

    public static Thermostat get(IsoLightSwitch lightSwitch) {
        RoomDef def = getRoomDef(lightSwitch);
        return def == null ? null : RoomTemperatureManager.getInstance().getThermostat(key(def.getBuilding()));
    }

    private static Optional<IsoThermalRoom> room(IsoLightSwitch lightSwitch) {
        RoomDef def = getRoomDef(lightSwitch);
        return def == null ? Optional.empty() : RoomTemperatureManager.getInstance().getSimulatedRoomById(def.getID());
    }

    public static boolean hasRoomTemperature(IsoLightSwitch lightSwitch) {
        return room(lightSwitch).isPresent();
    }

    public static float getRoomTemperature(IsoLightSwitch lightSwitch) {
        return room(lightSwitch).map(IsoThermalRoom::getCurrentTemperature).orElse(Float.NaN);
    }

    /** Grid power, or the power bank for RV interiors. Houses on a generator don't count. Uses the switch's square, so it works on clients. */
    public static boolean isPowered(IsoLightSwitch lightSwitch) {
        IsoGridSquare sq = lightSwitch.getSquare();
        return IsoThermalRoom.powerSourceAt(sq, ArcadiaRVInterior.isInterior(sq.getX(), sq.getY())) != IsoThermalRoom.PowerSource.NONE;
    }

    public static void request(IsoPlayer player, IsoLightSwitch lightSwitch, boolean on, float setPoint) {
        if (!isThermostat(lightSwitch)) return;
        if (GameClient.client) {
            INetworkPacket.send(GameClient.connection, PacketTypes.PacketType.Thermostat, player, lightSwitch, on, setPoint);
        } else {
            IsoGridSquare sq = lightSwitch.getSquare();
            RoomTemperatureManager.getInstance().handleThermostatRequest(player, sq.getX(), sq.getY(), sq.getZ(), on, setPoint);
        }
    }

    private static class ImmutableThermostat extends Thermostat {
        public ImmutableThermostat(boolean isOn, float setPoint) {
            super(isOn, setPoint);
        }

        @Override
        public void setOn(boolean isOn) {
            throw new UnsupportedOperationException("Thermostat is immutable");
        }

        @Override
        public void setSetPoint(float setPoint) {
            throw new UnsupportedOperationException("Thermostat is immutable");
        }
    }

}
