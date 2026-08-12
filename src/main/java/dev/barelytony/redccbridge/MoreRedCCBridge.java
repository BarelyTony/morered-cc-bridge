package dev.barelytony.redccbridge;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;

import dan200.computercraft.api.ComputerCraftAPI;
import net.commoble.exmachina.api.ExMachinaGameEvents;
import net.commoble.exmachina.api.ExMachinaRegistries;
import net.commoble.exmachina.api.SignalComponent;
import net.commoble.exmachina.api.StateWirer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent.NeighborNotifyEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Bridges More Red's bundled cables to CC: Tweaked's bundled redstone API.
 *
 * <p>The obvious design - bridging More Red's old {@code ChanneledPowerSupplier} capability to
 * CC's provider - is no longer possible. More Red dropped its {@code api} package entirely in the
 * 1.22 rewrite and handed all wire and cable connectivity to Commoble's Ex Machina framework, so
 * there is no capability left to bridge. Ex Machina's signal graph is the supported integration
 * point, and its channel model (sixteen dye colours plus plain redstone) happens to line up
 * exactly with CC's 16-bit bundled mask, one bit per {@code DyeColor} ordinal.
 *
 * <p>So the bridge is two halves that meet in {@link BundledSignalCache}:
 * {@link ComputerSignalComponent} puts computers into the graph on every colour channel, and
 * {@link MoreRedBundledProvider} answers CC's questions about what the adjacent cable carries.
 */
@Mod(MoreRedCCBridge.MODID)
public class MoreRedCCBridge {
    /** Must match the modId in neoforge.mods.toml. */
    public static final String MODID = "redcc_bridge";

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Ex Machina's signal component types are a static registry, so a DeferredRegister is the
     * right way in; the datamap refers to whatever we register here by id.
     */
    private static final DeferredRegister<MapCodec<? extends SignalComponent>> SIGNAL_COMPONENT_TYPES =
            DeferredRegister.create(ExMachinaRegistries.SIGNAL_COMPONENT_TYPE, MODID);

    /** {@code redcc_bridge:computer} - referenced by the signal_component datamap. */
    public static final DeferredHolder<MapCodec<? extends SignalComponent>, MapCodec<ComputerSignalComponent>>
            COMPUTER_SIGNAL_COMPONENT =
            SIGNAL_COMPONENT_TYPES.register("computer", () -> ComputerSignalComponent.CODEC);

    /**
     * FML passes the mod event bus in automatically.
     *
     * @param modEventBus this mod's event bus
     */
    public MoreRedCCBridge(IEventBus modEventBus) {
        SIGNAL_COMPONENT_TYPES.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(BundledSignalCache::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(MoreRedCCBridge::onNeighborNotify);
    }

    /**
     * Rebuilds the signal graph when a computer changes its bundled output.
     *
     * <p>Ex Machina never does this for us. Its own neighbour-notify handler enqueues only
     * <em>mechanical</em> graph updates; signal graphs are rebuilt solely in response to an
     * explicit {@link ExMachinaGameEvents#scheduleSignalGraphUpdate} game event. That is fine for
     * ordinary wires, whose blockstate changes when they change, but a computer's blockstate is
     * identical whether it is driving sixteen channels or none. So without this hook the cables
     * keep displaying whatever the graph last computed for some unrelated reason, and outputs
     * appear to stick, drop, or swap depending on what else happened to poke the network.
     *
     * <p>The filter is naturally tight: {@code getPos} is the block that caused the update, so
     * this only fires for computers changing their own output, not for every block that happens
     * to neighbour one.
     */
    private static void onNeighborNotify(NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var pos = event.getPos();
        if (StateWirer.getOrDefault(level, pos).component() instanceof ComputerSignalComponent) {
            ExMachinaGameEvents.scheduleSignalGraphUpdate(level, pos);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // CC's provider registry is not thread-safe, and common setup runs in parallel.
        event.enqueueWork(() -> {
            ComputerCraftAPI.registerBundledRedstoneProvider(new MoreRedBundledProvider());
            LOGGER.info("Registered More Red bundled redstone provider for CC: Tweaked");
        });
    }
}
