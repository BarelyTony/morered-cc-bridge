package dev.barelytony.redccbridge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.serialization.MapCodec;

import dan200.computercraft.api.ComputerCraftAPI;
import net.commoble.exmachina.api.Channel;
import net.commoble.exmachina.api.NodeShape;
import net.commoble.exmachina.api.SignalComponent;
import net.commoble.exmachina.api.SignalGraphKey;
import net.commoble.exmachina.api.TransmissionNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Makes CC: Tweaked computers participate in Ex Machina's signal graph on all sixteen colour
 * channels, which is what lets More Red's bundled cables see them.
 *
 * <p>Assigned to the computer blocks via the {@code exmachina:signal_component} block datamap
 * in {@code data/exmachina/data_maps/block/signal_component.json}.
 *
 * <p>Each node carries both halves of the bridge:
 * <ul>
 *   <li>its {@code source} publishes one bit of the computer's bundled output into the graph,
 *       which is how {@code redstone.setBundledOutput} reaches a cable;</li>
 *   <li>its {@code graphListener} records what the graph carries back, which is what
 *       {@code redstone.getBundledInput} ends up reading via {@link MoreRedBundledProvider}.</li>
 * </ul>
 *
 * <p>The node layout mirrors Ex Machina's own {@code CubeSignalComponent}: a full-cube block gets
 * a side-side node per (face, neighbour) pair so that a wire running along any face of any
 * neighbour can attach at the right spot.
 */
public enum ComputerSignalComponent implements SignalComponent {
    /** The singleton instance; the component carries no configuration. */
    INSTANCE;

    /** Codec for {@code redcc_bridge:computer}. The component has no fields, so it is a unit. */
    public static final MapCodec<ComputerSignalComponent> CODEC = MapCodec.unit(INSTANCE);

    /** Blocks a computer will attach bundled colour channels to. */
    public static final TagKey<Block> BUNDLED_CABLES =
            TagKey.create(Registries.BLOCK, Identifier.parse("redcc_bridge:bundled_cables"));

    @Override
    public MapCodec<? extends SignalComponent> codec() {
        return CODEC;
    }

    @Override
    public Collection<TransmissionNode> getTransmissionNodes(
            ResourceKey<Level> levelKey, BlockGetter level, BlockPos pos, BlockState state, Channel channel) {

        // Only the sixteen colour channels are bundled. The plain redstone channel is left alone
        // so that a computer's ordinary redstone output keeps working the way vanilla expects.
        if (!(channel instanceof Channel.Single single)) {
            return List.of();
        }

        var color = single.color();
        var nodePos = pos.immutable();
        var nodes = new ArrayList<TransmissionNode>();

        for (Direction face : Direction.values()) {
            for (Direction toNeighbor : Direction.values()) {
                if (toNeighbor == face || toNeighbor == face.getOpposite()) {
                    continue;
                }
                var fromNeighbor = toNeighbor.getOpposite();
                var neighborPos = nodePos.relative(toNeighbor);

                // Only ever reach toward an actual bundled cable.
                //
                // This is load-bearing, not an optimisation. Ex Machina walks
                // Channel#getConnectableChannels during graph construction, and the redstone
                // channel connects to ALL channels. So if a colour graph touches even one plain
                // redstone node, it absorbs all sixteen colours into a single graph - and a graph
                // carries exactly one power value, handed to every node's listener. Every colour
                // then reads and writes as one, which is precisely the "all colours are the same
                // wire" failure. Connecting only to colour-carrying cables keeps redstone out.
                if (!isBundledCable(level, neighborPos)) {
                    continue;
                }

                nodes.add(new TransmissionNode(
                        NodeShape.ofSideSide(face, toNeighbor),
                        reader -> bundledOutputBit(reader, nodePos, toNeighbor, color),
                        // No vanilla power readers: a bundled channel must not pick up loose
                        // redstone from adjacent blocks, or every colour would read as powered
                        // next to an active repeater.
                        Set.of(),
                        Set.of(new SignalGraphKey(
                                levelKey, neighborPos, NodeShape.ofSideSide(face, fromNeighbor), channel)),
                        (levelAccess, power) -> {
                            BundledSignalCache.put(levelKey, nodePos, color, power);
                            return Map.of();
                        }));
            }
        }

        return nodes;
    }

    /**
     * Always true, so the computer is block-updated after every graph update.
     *
     * <p>Without this the bridge looks broken in a very specific way: the cache tracks the cable
     * perfectly, but CC latches its bundled input when the computer starts and is never told to
     * look again. Readings are then correct at boot and frozen forever after - a lever flip
     * changes the graph, updates the cache, and never reaches the computer.
     *
     * <p>Ex Machina only block-updates a node's own position when this returns true (see
     * {@code SignalGraphBuffer#tick}, the {@code nodesUpdatingSelf} pass), and it defaults to
     * false. This is the same thing Ex Machina's built-in components expose as
     * {@code "receives_power": true} in their datamap.
     */
    @Override
    public boolean updateSelfFromNeighborsAfterGraphUpdate(LevelReader level, BlockState state, BlockPos pos) {
        return true;
    }

    /**
     * {@return true if the block at this position carries bundled colour channels}
     *
     * <p>Driven by the {@code redcc_bridge:bundled_cables} block tag rather than hardcoded ids, so
     * other mods' colour cables can opt in without a code change. A tag lookup is also
     * deliberately cheaper and safer than asking the neighbour for its own transmission nodes -
     * two adjacent computers would recurse into each other forever.
     */
    private static boolean isBundledCable(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).is(BUNDLED_CABLES);
    }

    /**
     * {@return 15 if the computer is driving this colour out of the given face, otherwise 0}
     *
     * <p>Bundled channels are binary, but Ex Machina's graph speaks in vanilla signal levels,
     * so a set bit is published at full strength.
     */
    private static int bundledOutputBit(LevelReader reader, BlockPos pos, Direction side, DyeColor color) {
        // ComputerCraftAPI needs a full Level. Graph queries during worldgen or chunk loading can
        // hand us a bare reader, in which case the computer simply is not driving anything yet.
        if (!(reader instanceof Level level)) {
            return 0;
        }

        var mask = ComputerCraftAPI.getBundledRedstoneOutput(level, pos, side);
        if (mask <= 0) {
            return 0;
        }

        return (mask & (1 << color.ordinal())) != 0 ? 15 : 0;
    }
}
