package dev.barelytony.redccbridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Records the signal level each colour channel carries at a given block position.
 *
 * <p>Ex Machina's signal graph is per-channel: a node exists at (level, pos, shape, channel),
 * and a graph update reports one power value for one channel at a time. CC: Tweaked, by
 * contrast, wants a single 16-bit mask. This class is the buffer between the two - each
 * channel's graph update sets or clears one bit, and the provider reads out the whole word.
 *
 * <p>Entries are written from {@link ComputerSignalComponent}'s graph listener and read back
 * by {@link MoreRedBundledProvider}. Keys are computer positions, not cable positions.
 */
public final class BundledSignalCache {
    private BundledSignalCache() {
    }

    private record Key(ResourceKey<Level> levelKey, BlockPos pos) {
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<Key, AtomicInteger> SIGNALS = new ConcurrentHashMap<>();

    /**
     * Records the power a single colour channel carries at a position.
     *
     * @param levelKey the level the node is in
     * @param pos      the position of the node (a computer, in practice)
     * @param color    the channel that just updated
     * @param power    the graph's new signal power for that channel, in [0,15]
     */
    public static void put(ResourceKey<Level> levelKey, BlockPos pos, DyeColor color, int power) {
        var key = new Key(levelKey, pos.immutable());
        var bit = 1 << color.ordinal();

        if (power > 0) {
            var entry = SIGNALS.computeIfAbsent(key, k -> new AtomicInteger());
            var before = entry.get();
            var after = entry.updateAndGet(mask -> mask | bit);
            logChange(pos, color, power, before, after);
            return;
        }

        // Clearing the last set bit should drop the entry entirely rather than leave a zero
        // behind, otherwise every position a cable ever touched leaks for the level's lifetime.
        var entry = SIGNALS.get(key);
        if (entry == null) {
            return;
        }
        var before = entry.get();
        var after = entry.updateAndGet(mask -> mask & ~bit);
        logChange(pos, color, power, before, after);
        if (after == 0) {
            SIGNALS.remove(key, entry);
        }
    }

    private static void logChange(BlockPos pos, DyeColor color, int power, int before, int after) {
        if (before != after) {
            LOGGER.debug("[redcc_bridge] graph update at {}: channel {} power {} -> mask {} (was {})",
                    pos, color.getSerializedName(), power, after, before);
        }
    }

    /**
     * {@return the 16-bit bundled mask at a position, or -1 if nothing is tracked there}
     *
     * <p>-1 rather than 0 is deliberate: it is CC's "not my block, ask someone else" sentinel,
     * and returning 0 would claim every block in the world as an unpowered bundled source.
     *
     * @param levelKey the level to look in
     * @param pos      the position to read
     */
    public static int maskOrAbsent(ResourceKey<Level> levelKey, BlockPos pos) {
        var entry = SIGNALS.get(new Key(levelKey, pos.immutable()));
        return entry == null ? -1 : entry.get();
    }

    /** Drops every entry belonging to a level as it unloads. */
    public static void onLevelUnload(LevelEvent.Unload event) {
        LevelAccessor accessor = event.getLevel();
        if (accessor instanceof Level level) {
            var levelKey = level.dimension();
            SIGNALS.keySet().removeIf(key -> key.levelKey().equals(levelKey));
        }
    }
}
