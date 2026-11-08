package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.ProjectKorra;
import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.*;

public class LightManager {

    // Our LightManager instance
    private static final LightManager INSTANCE = new LightManager();
    // Striped Lock set at number of processors * 2
    private final Object[] locks;
    // A map containing all active lights
    private final ConcurrentHashMap<Location, ConcurrentSkipListSet<LightData>> lightMap = new ConcurrentHashMap<>();
    // A queue for handling individual reversions
    private final ConcurrentLinkedQueue<BlockChange> blockChangeQueue = new ConcurrentLinkedQueue<>();

    // Default LIGHT BlockData
    private final Map<Integer, BlockData> lightDataMap = new HashMap<>();
    private final Map<Integer, BlockData> waterloggedLightDataMap = new HashMap<>();

    // Lights that are fading out, one brightness level per tick
    private final List<Fade> fades = new ArrayList<>();
    // Runs every tick on the main thread, since reverting and fading lights reads blocks and player locations
    private BukkitTask task;

    /**
     * Creates a new LightManager instance. Initializes default BlockData for LIGHT and waterlogged LIGHT and
     * sets up locks based on the number of available processors * 2. The tick task starts with the first light.
     */
    private LightManager() {
        int numLocks = Runtime.getRuntime().availableProcessors() * 2;
        locks = new Object[numLocks];
        for (int i = 0; i < numLocks; i++) {
            locks[i] = new Object();
        }

        precomputeLightData();
    }

    /**
     * Retrieves the current time and iterates over all light data in the light map. If the current time is greater
     * than or equal to the expiry time of a light data, it fades the light out and removes the light data from the map.
     * This runs every tick on the main thread.
     */
    private void revertExpiredLights() {
        long currentTime = System.currentTimeMillis();
        List<LightData> lightsToRevert = new ArrayList<>();

        lightMap.forEach((location, lightDataSet) -> {
            Iterator<LightData> iterator = lightDataSet.iterator();
            while (iterator.hasNext()) {
                LightData lightData = iterator.next();
                if (currentTime >= lightData.expiryTime) {
                    lightsToRevert.add(lightData);
                    iterator.remove();
                }
            }
            if (lightDataSet.isEmpty()) {
                lightMap.remove(location);
            }
        });

        for (LightData lightData : lightsToRevert) {
            fadeLight(lightData);
        }
    }

    /**
     * Precomputes light data for levels 1 through 15 by creating a BlockData object for each level
     * with the "LIGHT" material and setting the level using the Levelled interface. It also
     * creates a waterlogged version of each light data object using the Waterlogged interface.
     * The resulting objects are stored in the lightDataMap and waterloggedLightDataMap maps
     * respectively. This cuts down on computation time constantly manipulating BlockData.
     */
    private void precomputeLightData() {
        BlockData lightData = Bukkit.createBlockData(Material.valueOf("LIGHT"));

        for (int level = 1; level <= 15; level++) {
            ((Levelled) lightData).setLevel(level);
            lightDataMap.put(level, lightData.clone());

            BlockData waterloggedLightData = lightData.clone();
            ((Waterlogged) waterloggedLightData).setWaterlogged(true);
            waterloggedLightDataMap.put(level, waterloggedLightData);
        }
    }

    /**
     * Starts the task that reverts expired lights, fades them out and sends the block changes, if it isn't running.
     * It runs every tick on the main thread. A reload cancels all of ProjectKorra's tasks, so the next light
     * starts it again.
     */
    private void startLightReverter() {
        if (this.task == null || this.task.isCancelled()) {
            this.task = Bukkit.getScheduler().runTaskTimer(ProjectKorra.plugin, this::tick, 0, 1);
        }
    }

    private void tick() {
        revertExpiredLights();
        progressFades();
        processBlockChanges();
    }

    /**
     * Fades out a light by decrementing its brightness by 1 each tick. The light will stop fading when its
     * brightness reaches 0. The first step happens in the same tick the light expires.
     *
     * @param lightData the LightData object containing the light's brightness, location, UUID, and ephemeral flag
     */
    private void fadeLight(LightData lightData) {
        fades.add(new Fade(lightData));
    }

    private void progressFades() {
        final Iterator<Fade> iterator = fades.iterator();
        while (iterator.hasNext()) {
            final Fade fade = iterator.next();
            fade.brightness--;
            if (fade.brightness > 0) {
                sendLightChange(fade.lightData.location, fade.brightness, fade.lightData.observers);
            } else {
                revertLight(fade.lightData);
                iterator.remove();
            }
        }
    }

    private static class Fade {
        private final LightData lightData;
        private int brightness;

        private Fade(LightData lightData) {
            this.lightData = lightData;
            this.brightness = lightData.brightness;
        }
    }

    /**
     * Sends a block change to the specified location. Brightness of 0 indicates that the light should be reverted.
     *
     * @param location   the location where the light change is to be sent
     * @param brightness the brightness level of the light
     * @param observers  the list of players who can see the light
     */
    private void sendLightChange(Location location, int brightness, Collection<? extends Player> observers) {
        BlockData lightData = brightness > 0 ? getLightData(location, brightness) : getCurrentBlockData(location);
        World targetWorld = location.getWorld();
        double maxDistanceSquared = Math.pow(Bukkit.getServer().getViewDistance() * 16, 2);

        observers.stream()
                .filter(player -> player != null && player.isOnline() && !player.isDead() && player.getWorld().equals(targetWorld))
                .filter(player -> player.getLocation().distanceSquared(location) <= maxDistanceSquared)
                .forEach(player -> blockChangeQueue.add(new BlockChange(player, location, lightData)));
    }

    /**
     * Sends all queued light changes, batched into one multi block change per player. If a location changed more
     * than once since the last batch, only its latest state is sent.
     */
    private void processBlockChanges() {
        final Map<Player, Map<BlockPosition, BlockData>> changes = new HashMap<>();
        BlockChange blockChange;
        while ((blockChange = blockChangeQueue.poll()) != null) {
            changes.computeIfAbsent(blockChange.getPlayer(), player -> new LinkedHashMap<>())
                    .put(Position.block(blockChange.getLocation()), blockChange.getBlockData());
        }
        changes.forEach((player, blocks) -> {
            if (player.isOnline()) {
                player.sendMultiBlockChange(blocks);
            }
        });
    }

    /**
     * Helper method to revert a light at the specified location by calling sendLightChange with a brightness of 0.
     *
     * @param lightData the LightData object containing the location, brightness, UUID, and ephemeral flag of the light to be reverted
     */
    private void revertLight(LightData lightData) {
        sendLightChange(lightData.location, 0, lightData.observers);
    }

    /**
     * Returns the BlockData as light for the given Location, based on whether the block is water or air.
     *
     * @param location   the Location to get the BlockData for
     * @param lightLevel the light level to set for the BlockData
     * @return the BlockData for the given Location
     */
    private BlockData getLightData(Location location, int lightLevel) {
        if (location.getBlock().getType() == Material.WATER) {
            return waterloggedLightDataMap.get(lightLevel);
        } else {
            return lightDataMap.get(lightLevel);
        }
    }

    /**
     * Returns the BlockData for the given Location, based on the current state of the block.
     * Used to revert lights that have expired.
     *
     * @param location the Location to get the BlockData for
     * @return the BlockData for the given Location
     */
    private BlockData getCurrentBlockData(Location location) {
        return location.getBlock().getBlockData();
    }

    public static LightManager get() {
        return INSTANCE;
    }

    /**
     * Creates a new LightBuilder instance with the given location.
     *
     * @param location the location where the light will be created
     * @return a new LightBuilder instance
     */
    public static LightBuilder createLight(Location location) {
        return new LightBuilder(location);
    }

    /**
     * Adds a light at the specified location with the given brightness and expiry.
     * Visible for the specified observers.
     * Subsequent calls to a location with an active light extends the expiration time for the relevant observers.
     *
     * @param location   the location where the light should be added
     * @param brightness the brightness of the light, 1-15
     * @param expiry     the time in milliseconds before the light fades out
     * @param observers  the list of players who can see the light
     */
    private void addLight(Location location, int brightness, long expiry, Collection<? extends Player> observers) {
        startLightReverter();
        location = location.getBlock().getLocation();
        long expiryTime = System.currentTimeMillis() + expiry;

        if (location.getBlock().getLightLevel() >= brightness ||
                (!location.getBlock().isEmpty() && !location.getBlock().getType().equals(Material.WATER))) return;

        // The default observers are the server's live online player list; copy it so later joins and quits
        // don't change who this light was sent to
        final List<Player> viewers = new ArrayList<>(observers);

        LightData newLightData = new LightData(location, brightness, viewers, expiryTime);

        Object lock = getLockForLocation(location);
        synchronized (lock) {
            ConcurrentSkipListSet<LightData> existingSet = lightMap.computeIfAbsent(location, loc -> new ConcurrentSkipListSet<>());
            existingSet.removeIf(lightData -> lightData.observers.equals(viewers));
            existingSet.add(newLightData);
        }

        sendLightChange(location, brightness, viewers);
    }

    /**
     * Returns the lock object associated with the given location. The lock object
     * is used to synchronize access to the light data for the location. The
     * function calculates the hash code of the location and uses it to determine
     * the index of the lock object in the locks array. The locks array is
     * initialized with a fixed number of objects based on the number of available
     * processors * 2.
     *
     * @param location the location for which the lock object is requested
     * @return the lock object associated with the location
     */
    private Object getLockForLocation(Location location) {
        return locks[(location.hashCode() & 0x7FFFFFFF) % locks.length];
    }

    /**
     * Reverts all active lights immediately with no fade-out, then restarts the revert scheduler.
     * This does not normally need to be used as it's already called when ProjectKorra is reloaded.
     */
    public void restart() {
        lightMap.values().forEach(set -> set.forEach(this::revertLight));
        lightMap.clear();
        fades.forEach(fade -> revertLight(fade.lightData));
        fades.clear();
        processBlockChanges();

        startLightReverter();
    }

    private static class LightData implements Comparable<LightData> {
        private final Location location;
        private final int brightness;
        private final Collection<? extends Player> observers;
        private final long expiryTime;

        private LightData(Location location, int brightness, Collection<? extends Player> observers, long expiryTime) {
            this.location = location;
            this.brightness = brightness;
            this.observers = observers;
            this.expiryTime = expiryTime;
        }

        /**
         * Calculates the hash code for this object. The hash code is based on the
         * values of the location, brightness, observers, and expiryTime fields.
         *
         * @return the hash code of this object
         */
        @Override
        public int hashCode() {
            return Objects.hash(location, brightness, observers, expiryTime);
        }

        /**
         * Checks if this LightData object is equal to another object. Two LightData objects are considered
         * equal if they have the same brightness, expiryTime, location, and observers.
         *
         * @param obj the object to compare this LightData object to
         * @return true if the objects are equal, false otherwise
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            LightData that = (LightData) obj;
            return brightness == that.brightness &&
                    expiryTime == that.expiryTime &&
                    location.equals(that.location) &&
                    observers.equals(that.observers);
        }

        /**
         * Returns a string representation of the LightData object.
         *
         * @return a string in the format "LightData{location=..., brightness=..., observers=..., expiryTime=...}"
         */
        @Override
        public String toString() {
            return "LightData{" +
                    "location=" + location +
                    ", brightness=" + brightness +
                    ", observers=" + observers +
                    ", expiryTime=" + expiryTime +
                    '}';
        }

        /**
         * Compares this LightData object with another LightData object based on their expiryTime.
         *
         * @param other the LightData object to compare to
         * @return a negative integer, zero, or a positive integer as this object's
         * expiryTime is less than, equal to, or greater than the other object's
         * expiryTime.
         */
        @Override
        public int compareTo(LightData other) {
            return Long.compare(this.expiryTime, other.expiryTime);
        }
    }

    public static class LightBuilder {
        private final Location location;
        private int brightness = 15; // default brightness
        private long timeUntilFade = 50; // default expiry time in ms
        private Collection<? extends Player> observers = Bukkit.getOnlinePlayers(); // default to all players

        public LightBuilder(Location location) {
            this.location = location;
        }

        /**
         * Sets the brightness value, 1-15, for this light.
         *
         * @param brightness the new brightness value, a value 1-15.
         * @return the current instance of the LightBuilder
         */
        public LightBuilder brightness(int brightness) {
            this.brightness = Math.max(1, Math.min(15, brightness));
            return this;
        }

        /**
         * Sets the expiry time in ms for this light, defaults to 50ms.
         *
         * @param expiry the new expiry time in milliseconds
         * @return the current instance of the LightBuilder
         */
        public LightBuilder timeUntilFadeout(long expiry) {
            this.timeUntilFade = expiry;
            return this;
        }

        /**
         * Sets the collection of observers for this light, defaults to everybody.
         *
         * @param observers the collection of observers to set
         * @return the current instance of the LightBuilder
         */
        public LightBuilder observers(Collection<? extends Player> observers) {
            this.observers = observers;
            return this;
        }

        /**
         * Emits this light at the specified location with the given brightness, expiry time, and observers.
         */
        public void emit() {
            LightManager.get().addLight(location, brightness, timeUntilFade, observers);
        }
    }

    private static class BlockChange {
        private final Player player;
        private final Location location;
        private final BlockData blockData;

        public BlockChange(Player player, Location location, BlockData blockData) {
            this.player = player;
            this.location = location;
            this.blockData = blockData;
        }

        public Player getPlayer() {
            return player;
        }

        public Location getLocation() {
            return location;
        }

        public BlockData getBlockData() {
            return blockData;
        }
    }
}