package dev.barelytony.redccbridge;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dan200.computercraft.api.redstone.BundledRedstoneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Reports a More Red cable's bundled signal to CC: Tweaked, which is what makes
 * {@code redstone.getBundledInput} return something on a computer sitting next to one.
 *
 * <p>CC asks this question from the computer's point of view: given the neighbouring block at
 * {@code pos}, what is it emitting out of the face {@code side}? So the computer doing the asking
 * is at {@code pos.relative(side)} - which is exactly the key
 * {@link ComputerSignalComponent} caches its graph updates under.
 *
 * <p>That indirection is deliberate. Since the 1.22 rewrite More Red keeps its per-channel cable
 * state in internals with no public accessor ({@code WireBlockEntity}, {@code ChannelSet}), and
 * 26.1.x is not published to any Maven, so there is nothing stable to read from the cable itself.
 * Reading the shared graph value from our own node instead gets the same number without compiling
 * against a single More Red class.
 */
public final class MoreRedBundledProvider implements BundledRedstoneProvider {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Namespace of the mod whose cables we answer for. */
    private static final String MORE_RED = "morered";

    @Override
    public int getBundledRedstoneOutput(Level world, BlockPos pos, Direction side) {
        var state = world.getBlockState(pos);
        var key = state.typeHolder().getKey();

        // -1 is CC's "not handled" sentinel, which lets other providers answer instead.
        // Note: MC 26.x renamed ResourceLocation to Identifier, and ResourceKey#location to
        // ResourceKey#identifier. This is identifier(), not location().
        if (key == null || !MORE_RED.equals(key.identifier().getNamespace())) {
            return -1;
        }

        var requesterPos = pos.relative(side);
        var mask = BundledSignalCache.maskOrAbsent(world.dimension(), requesterPos);
        // Debug level: CC queries this every time a computer re-reads its input, so it is far too
        // hot for info. Enable redcc_bridge at debug in log4j2.xml if you need to trace reads.
        LOGGER.debug("[redcc_bridge] CC asked cable {} (side {}) -> requester {} -> mask {}",
                pos, side, requesterPos, mask);
        return mask;
    }
}
