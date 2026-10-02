package dev.rancraft.world;

import dev.rancraft.RanCraft;
import dev.rancraft.RanCraftConfig;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.block.BackhaulDishBlockEntity;
import dev.rancraft.block.SectorAntennaBlock;
import dev.rancraft.block.SignalMastBlock;
import dev.rancraft.data.MaterialTable;
import dev.rancraft.data.RfDataLoader;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.net.BackhaulLinksPayload;
import dev.rancraft.registry.ModBlocks;
import dev.rancraft.rf.BackhaulGraph;
import dev.rancraft.rf.BackhaulGraph.BackhaulState;
import dev.rancraft.rf.MicrowaveLink;
import dev.rancraft.rf.ReceiverState;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.ServiceLevel;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * One dimension's backhaul (Phase 3 slice 12, §3C.2): the Core Sites, the Backhaul Dishes and their
 * pairings, the cells that could transmit, each microwave hop's measured budget, and each cell's
 * backhaul state ({@link BackhaulGraph}). The server's half of the pure {@code rf} pair
 * {@link MicrowaveLink} and {@link BackhaulGraph}.
 *
 * <p><b>The topology is saved with the dimension</b> ({@link SavedData}, {@code data/rancraft_backhaul.dat}):
 * cores, dishes, pairings and the cells' bases. A microwave hop is a few hundred blocks to a kilometre
 * long, so one end of it is usually in a chunk nobody has loaded, and so may the core or a relay site
 * in the middle of a chain. They still exist: a block cannot change while its chunk is unloaded, so the
 * last thing the block told the network stays true. Entries are added and removed by the blocks' own
 * lifecycle paths (as the site registry is), never by a chunk unloading:
 * <ul>
 *   <li>cores: {@code block.CoreSiteBlockEntity} when it joins a chunk, and when the block is broken or
 *       replaced;</li>
 *   <li>dishes: {@code block.BackhaulDishBlockEntity} likewise, and the Link Tool's pairings
 *       ({@link #pair}, {@link #unpair}). The network is the record of who is paired with whom (a dish
 *       paired while its partner's chunk was unloaded has a stale saved partner); each loaded dish's
 *       entity mirrors it ({@link #dishLoaded});</li>
 *   <li>cells: {@link AntennaBlockEntity#refreshRegistration()} reports every cell that passes its own
 *       rules (its column base, its redstone), and removal of the antenna drops it. The graph needs
 *       these, not the site registry: a cell taken off the air for want of backhaul is gone from the
 *       registry, and must still be found when its backhaul comes back; and a relay site in an unloaded
 *       chunk still switches between its dishes.</li>
 * </ul>
 * An entry whose block is gone when its chunk loads (an edited world) is dropped then
 * ({@link #validate}).
 *
 * <p><b>Recompute</b> (§3C.2): when the site registry's version moves, when a dish is paired or
 * unpaired (or a core, dish or cell comes or goes), when a region epoch moves on a bin any hop crosses
 * ({@link MicrowaveLink#dependencyBins}, {@link RegionEpochs}), when the weather changes, or when the
 * link figures, the radii or {@code requireBackhaul} change; and at most once per
 * {@code backhaulRecomputeTicks}. A change waits for the next allowed tick; a quiet network costs a few
 * comparisons per tick. Only a hop whose bins moved (or a new one) is marched again; a weather change
 * re-budgets every hop without reading a block ({@link MicrowaveLink#withWeather}); the graph is solved
 * again whenever anything changed.
 *
 * <p><b>Effects</b> (§3C.2), only with {@code requireBackhaul} on ({@link BackhaulGraph#allowsOnAir},
 * {@link BackhaulGraph#serviceCap}): a NONE cell is not transmitting, so its antenna unregisters it and
 * its {@code OnAir} goes false ({@link AntennaBlockEntity#isTransmitting()}, refreshed from here, so the
 * registry and the lens cannot disagree); a LIMITED cell transmits, but devices it serves are capped at
 * FAIR in their {@code DeviceContext} ({@link #serviceCapAt}). With it off (the default) nothing a cell
 * or a device does changes and no antenna is refreshed; the states are still worked out for the lens,
 * the dish and the status command once a core or dish exists. A dimension with no core, no dish and
 * {@code requireBackhaul} off costs one map lookup per tick.
 *
 * <p><b>Game abstractions, labelled (NOTES.md, slice 12):</b>
 * <ul>
 *   <li><b>Unloaded terrain reads as air</b>, as for every cellular ray ({@link LevelWorldProbe}): the
 *       stretch of a hop that crosses an unloaded chunk is measured as clear. The chunk's bin moves
 *       when it loads or unloads, and the hop is measured again then.</li>
 *   <li><b>A cell the graph has not judged yet is on the air</b> (an unknown state; it is judged at
 *       the next recompute). A newly placed cell with no backhaul therefore transmits until then, at
 *       most {@code backhaulRecomputeTicks}; so do the cells loaded before the first recompute after a
 *       server start (one tick).</li>
 *   <li><b>The weather at a hop</b> is the level's rain and thunder with the precipitation of the
 *       biome at the hop's midpoint ({@code Biome.getPrecipitationAt}), read from the midpoint's 4 x 4
 *       x 4 biome cell (the loaded chunk's, or the generator's), without vanilla's fuzzy zoom. Read
 *       when the hop is measured: a biome does not change under a hop.</li>
 *   <li><b>A hop longer than {@code maxEvaluationRangeBlocks}</b> (1400 by default) is DOWN, out of
 *       range: the Link Tool refuses to pair one, and a hop that a lowered range leaves too long is
 *       not marched. It bounds the cost of a march, as the same setting bounds a cellular one.</li>
 * </ul>
 *
 * <p>Server thread only, like everything that reads the level.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID)
public final class BackhaulNetwork extends SavedData {

    /** File name in the dimension's {@code data/} folder. */
    public static final String DATA_NAME = "rancraft_backhaul";

    /** Save format of {@link #save}. <b>1</b>: Phase 3 slice 12. */
    public static final int DATA_VERSION = 1;

    /** Ticks between two looks at what each lens wearer should see: once a second. */
    static final int LENS_SEND_INTERVAL_TICKS = 20;

    private static final SavedData.Factory<BackhaulNetwork> FACTORY =
            new SavedData.Factory<>(BackhaulNetwork::new, BackhaulNetwork::load);

    /** What each player was last sent, so an unchanged set is not sent again (an empty set is sent once). */
    private static final Map<UUID, List<BackhaulLinksPayload.Link>> LENS_SENT = new ConcurrentHashMap<>();

    // ---- inputs (saved) ---------------------------------------------------------------------------

    private final LongOpenHashSet cores = new LongOpenHashSet();
    private final LongOpenHashSet dishes = new LongOpenHashSet();
    /** A dish's partner; absent when it is not paired. Kept mutual by {@link #pair} and {@link #unpair}. */
    private final Long2LongOpenHashMap partners = new Long2LongOpenHashMap();
    /** Cells that pass their own rules (would be on the air but for their backhaul), by cell id. */
    private final Long2ObjectOpenHashMap<BackhaulGraph.Node> cells = new Long2ObjectOpenHashMap<>();
    /** Bumped on every change to the four inputs above. Not saved: a fresh load solves anyway. */
    private long topologyVersion;

    // ---- derived ----------------------------------------------------------------------------------

    /** Hops by their two dishes, lower packed position first. */
    private final Map<HopKey, Hop> hops = new LinkedHashMap<>();
    private Map<Long, BackhaulState> cellStates = Map.of();
    private Map<Long, BackhaulState> dishStates = Map.of();
    private boolean solved;
    private long solvedTopologyVersion = -1L;
    private long solvedSiteVersion = -1L;
    private BackhaulGraph.Topology solvedTopology;
    private long lastRecomputeTick;
    private long checkedBumps = -1L;
    private boolean raining;
    private boolean thundering;
    private boolean rainFade;
    private MicrowaveLink measuredWith;
    private MaterialTable measuredMaterials;
    private double measuredMetersPerBlock = Double.NaN;
    private double measuredRangeBlocks = Double.NaN;
    /** {@code requireBackhaul} as the last recompute applied it; null before the first. */
    private Boolean appliedRequire;

    // ---- statistics (game tests and the status command) -------------------------------------------

    private long recomputes;
    private int lastMarched;
    private long lastRecomputeNanos;

    /** A hop's two dishes, {@code a < b}: one fixed order, so both ends always agree (slice 11 note). */
    record HopKey(long a, long b) {

        static HopKey of(long dish, long partner) {
            return dish < partner ? new HopKey(dish, partner) : new HopKey(partner, dish);
        }
    }

    /** One hop's measurement. */
    private static final class Hop {

        final long a;
        final long b;
        /** Measured with clear weather; the weather is applied on top ({@link MicrowaveLink#withWeather}). */
        MicrowaveLink.Budget clear;
        /** With the weather of the last recompute. */
        MicrowaveLink.Budget budget;
        MicrowaveLink.Weather weather = MicrowaveLink.Weather.CLEAR;
        /** What the biome at the midpoint makes of rain: CLEAR (none), RAIN or SNOW. */
        MicrowaveLink.Weather precipitation = MicrowaveLink.Weather.CLEAR;
        RegionEpochs.Snapshot dependencies = RegionEpochs.Snapshot.NONE;

        Hop(long a, long b) {
            this.a = a;
            this.b = b;
        }
    }

    /**
     * One hop as last measured, for the lens, the dish and the status command.
     *
     * @param a       the end with the lower packed position (the hop is always marched from it).
     * @param weather the weather at its midpoint at the last recompute (also when rain fade is off;
     *                the budget's rain loss is then 0).
     */
    public record HopStatus(BlockPos a, BlockPos b, MicrowaveLink.Budget budget, MicrowaveLink.Weather weather) {

        /** The other end of the hop from {@code dish}. */
        public BlockPos partnerOf(BlockPos dish) {
            return dish.equals(a) ? b : a;
        }
    }

    /** A cell the graph knows, with its state at the last solve ({@code null}: not judged yet). */
    public record CellStatus(long cellId, BackhaulState state) {

        public BlockPos base() {
            return BlockPos.of(cellId);
        }
    }

    BackhaulNetwork() {
    }

    /** The network of one dimension, created (or read from the save) on first use. */
    public static BackhaulNetwork of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * The network of one dimension, or {@code null} when the dimension has none (nothing has used it
     * and nothing is saved). Creates nothing.
     */
    public static @Nullable BackhaulNetwork peek(ServerLevel level) {
        return level.getDataStorage().get(FACTORY, DATA_NAME);
    }

    // ---- save -------------------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putLongArray("Cores", sorted(cores.toLongArray()));
        tag.putLongArray("Dishes", sorted(dishes.toLongArray()));
        long[] pairs = new long[partners.size() * 2];
        int i = 0;
        for (long dish : sorted(partners.keySet().toLongArray())) {
            pairs[i++] = dish;
            pairs[i++] = partners.get(dish);
        }
        tag.putLongArray("Partners", pairs);
        tag.putLongArray("Cells", sorted(cells.keySet().toLongArray()));
        return tag;
    }

    /** Reads a saved network; a pairing naming a dish that is not listed is dropped. */
    static BackhaulNetwork load(CompoundTag tag, HolderLookup.Provider registries) {
        BackhaulNetwork network = new BackhaulNetwork();
        for (long core : tag.getLongArray("Cores")) {
            network.cores.add(core);
        }
        for (long dish : tag.getLongArray("Dishes")) {
            network.dishes.add(dish);
        }
        long[] pairs = tag.getLongArray("Partners");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (network.dishes.contains(pairs[i]) && pairs[i] != pairs[i + 1]) {
                network.partners.put(pairs[i], pairs[i + 1]);
            }
        }
        for (long cell : tag.getLongArray("Cells")) {
            network.cells.put(cell, node(cell));
        }
        return network;
    }

    private static long[] sorted(long[] values) {
        Arrays.sort(values);
        return values;
    }

    private void changed() {
        topologyVersion++;
        setDirty();
    }

    // ---- inputs: cores ----------------------------------------------------------------------------

    public void coreLoaded(BlockPos pos) {
        if (cores.add(pos.asLong())) {
            changed();
        }
    }

    /** The core was broken or replaced (not unloaded). */
    public void coreRemoved(BlockPos pos) {
        if (cores.remove(pos.asLong())) {
            changed();
        }
    }

    // ---- inputs: dishes ---------------------------------------------------------------------------

    /**
     * A dish's entity joined a chunk. A dish the network knows takes the network's partner (it may have
     * been paired or unpaired while it was unloaded); one it does not know is added with its own saved
     * partner (a world whose backhaul file is missing).
     *
     * @return the partner the entity should hold now, or {@code null}.
     */
    public @Nullable BlockPos dishLoaded(BlockPos pos, @Nullable BlockPos savedPartner) {
        long key = pos.asLong();
        if (dishes.contains(key)) {
            return partnerOf(pos);
        }
        dishes.add(key);
        if (savedPartner != null && savedPartner.asLong() != key) {
            partners.put(key, savedPartner.asLong());
        }
        changed();
        return savedPartner;
    }

    /** The dish was broken or replaced (not unloaded): it leaves, and its partner is unpaired. */
    public void dishRemoved(ServerLevel level, BlockPos pos) {
        unpair(level, pos);
        if (dishes.remove(pos.asLong())) {
            changed();
        }
    }

    /**
     * Pairs two dishes (the Link Tool, §3C.2): each forgets its old partner (whose own pairing is
     * cleared too), then each holds the other. Loaded dish entities are updated at once; an unloaded one
     * takes its partner from here when it loads ({@link #dishLoaded}). The caller checks that both are
     * dishes and the hop's length.
     */
    public void pair(ServerLevel level, BlockPos a, BlockPos b) {
        for (long changed : pairEntries(a.asLong(), b.asLong())) {
            mirrorToEntity(level, changed);
        }
    }

    /** Unpairs a dish and its partner. False when it was not paired. */
    public boolean unpair(ServerLevel level, BlockPos dish) {
        LongList changedDishes = unpairEntries(dish.asLong());
        for (long changed : changedDishes) {
            mirrorToEntity(level, changed);
        }
        return !changedDishes.isEmpty();
    }

    /** {@link #pair}'s bookkeeping, without the entities: returns every dish whose partner changed. */
    LongList pairEntries(long a, long b) {
        if (a == b) {
            throw new IllegalArgumentException("a dish cannot be paired with itself: " + BlockPos.of(a));
        }
        LongList changedDishes = new LongArrayList();
        if (partners.containsKey(a) && partners.get(a) == b && partners.containsKey(b) && partners.get(b) == a) {
            return changedDishes;
        }
        changedDishes.addAll(unpairEntries(a));
        changedDishes.addAll(unpairEntries(b));
        dishes.add(a);
        dishes.add(b);
        partners.put(a, b);
        partners.put(b, a);
        changedDishes.add(a);
        changedDishes.add(b);
        changed();
        return changedDishes;
    }

    /** {@link #unpair}'s bookkeeping: clears the dish's partner and, if it points back, the partner's. */
    LongList unpairEntries(long dish) {
        LongList changedDishes = new LongArrayList();
        if (!partners.containsKey(dish)) {
            return changedDishes;
        }
        long partner = partners.remove(dish);
        changedDishes.add(dish);
        if (partners.containsKey(partner) && partners.get(partner) == dish) {
            partners.remove(partner);
            changedDishes.add(partner);
        }
        changed();
        return changedDishes;
    }

    /** A dish's partner as the network records it, or {@code null}. */
    public @Nullable BlockPos partnerOf(BlockPos dish) {
        long key = dish.asLong();
        return partners.containsKey(key) ? BlockPos.of(partners.get(key)) : null;
    }

    /** Hands a loaded dish entity its partner from the network. Never loads a chunk. */
    private void mirrorToEntity(ServerLevel level, long dish) {
        BlockPos pos = BlockPos.of(dish);
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        if (chunk != null && chunk.getBlockEntity(pos) instanceof BackhaulDishBlockEntity entity) {
            entity.partnerFromNetwork(partnerOf(pos));
        }
    }

    // ---- inputs: cells ----------------------------------------------------------------------------

    /**
     * A cell's antenna reports whether it passes its own rules ({@code eligible}). Called on every
     * registration refresh; a repeat changes nothing.
     */
    public void noteCell(long cellId, boolean eligible) {
        if (eligible) {
            if (!cells.containsKey(cellId)) {
                cells.put(cellId, node(cellId));
                changed();
            }
        } else {
            removeCell(cellId);
        }
    }

    /** The cell's antenna was broken, or is no longer a cell (not unloaded). */
    public void removeCell(long cellId) {
        if (cells.remove(cellId) != null) {
            changed();
        }
    }

    /**
     * Drops the entries in a chunk that has just loaded whose block is not there any more (a world
     * edited while the chunk was unloaded). A block that is there re-reports itself through its entity.
     */
    void validate(ServerLevel level, LevelChunk chunk) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        List<Long> staleCores = new ArrayList<>();
        for (long core : cores) {
            if (inChunk(core, chunkX, chunkZ) && !chunk.getBlockState(BlockPos.of(core)).is(ModBlocks.CORE_SITE.get())) {
                staleCores.add(core);
            }
        }
        List<Long> staleDishes = new ArrayList<>();
        for (long dish : dishes) {
            if (inChunk(dish, chunkX, chunkZ)
                    && !chunk.getBlockState(BlockPos.of(dish)).is(ModBlocks.BACKHAUL_DISH.get())) {
                staleDishes.add(dish);
            }
        }
        List<Long> staleCells = new ArrayList<>();
        for (long cell : cells.keySet()) {
            if (inChunk(cell, chunkX, chunkZ)) {
                Block block = chunk.getBlockState(BlockPos.of(cell)).getBlock();
                if (!(block instanceof SignalMastBlock) && !(block instanceof SectorAntennaBlock)) {
                    staleCells.add(cell);
                }
            }
        }
        for (long core : staleCores) {
            cores.remove(core);
            changed();
        }
        for (long dish : staleDishes) {
            unpair(level, BlockPos.of(dish));
            dishes.remove(dish);
            changed();
        }
        for (long cell : staleCells) {
            removeCell(cell);
        }
        if (!staleCores.isEmpty() || !staleDishes.isEmpty() || !staleCells.isEmpty()) {
            RanCraft.LOGGER.info("RANCraft backhaul: dropped {} core(s), {} dish(es) and {} cell(s) whose blocks are "
                    + "gone from chunk {}, {}", staleCores.size(), staleDishes.size(), staleCells.size(), chunkX, chunkZ);
        }
    }

    private static boolean inChunk(long pos, int chunkX, int chunkZ) {
        return SectionPos.blockToSectionCoord(BlockPos.getX(pos)) == chunkX
                && SectionPos.blockToSectionCoord(BlockPos.getZ(pos)) == chunkZ;
    }

    // ---- queries ----------------------------------------------------------------------------------

    /** A cell's backhaul at the last solve, or {@code null} when it has not been judged yet. */
    public @Nullable BackhaulState stateOf(long cellId) {
        return cellStates.get(cellId);
    }

    /** A dish's own reach to the core at the last solve, or {@code null} when not judged yet. */
    public @Nullable BackhaulState dishStateOf(BlockPos dish) {
        return dishStates.get(dish.asLong());
    }

    /** Whether the cell may be on the air now ({@link BackhaulGraph#allowsOnAir}). */
    public boolean allowsOnAir(long cellId) {
        return BackhaulGraph.allowsOnAir(stateOf(cellId), RanCraftConfig.requireBackhaul());
    }

    /** The cap the cell's backhaul puts on the devices it serves now ({@link BackhaulGraph#serviceCap}). */
    public ServiceLevel serviceCap(long cellId) {
        return BackhaulGraph.serviceCap(stateOf(cellId), RanCraftConfig.requireBackhaul());
    }

    /**
     * Whether a cell may be on the air now, in a level that may have no network (then it may: there is
     * no verdict on it).
     */
    public static boolean allowsOnAir(ServerLevel level, long cellId) {
        BackhaulNetwork network = peek(level);
        return network == null
                ? BackhaulGraph.allowsOnAir(null, RanCraftConfig.requireBackhaul())
                : network.allowsOnAir(cellId);
    }

    /**
     * The service cap for a device served by {@code servingCellId} (§3C.2: LIMITED caps at FAIR), read
     * at dispatch time. No cap without a serving cell or without a network.
     */
    public static ServiceLevel serviceCapAt(ServerLevel level, long servingCellId) {
        if (servingCellId == ReceiverState.NO_CELL) {
            return ServiceLevel.EXCELLENT;
        }
        BackhaulNetwork network = peek(level);
        return network == null ? ServiceLevel.EXCELLENT : network.serviceCap(servingCellId);
    }

    public boolean isCore(BlockPos pos) {
        return cores.contains(pos.asLong());
    }

    public boolean isDish(BlockPos pos) {
        return dishes.contains(pos.asLong());
    }

    public boolean isCell(long cellId) {
        return cells.containsKey(cellId);
    }

    public int coreCount() {
        return cores.size();
    }

    public int dishCount() {
        return dishes.size();
    }

    /** Every core, as packed positions. */
    public long[] cores() {
        return cores.toLongArray();
    }

    /** Every cell the graph knows (loaded or not), with its state at the last solve. */
    public List<CellStatus> cells() {
        List<CellStatus> out = new ArrayList<>(cells.size());
        for (long cellId : cells.keySet()) {
            out.add(new CellStatus(cellId, cellStates.get(cellId)));
        }
        return out;
    }

    /** Every hop as last measured (empty before the first recompute). */
    public List<HopStatus> hops() {
        List<HopStatus> out = new ArrayList<>(hops.size());
        for (Hop hop : hops.values()) {
            if (hop.budget != null) {
                out.add(status(hop));
            }
        }
        return out;
    }

    /** The hop this dish is an end of, as last measured, or {@code null}. */
    public @Nullable HopStatus hopOf(BlockPos dish) {
        long key = dish.asLong();
        if (!partners.containsKey(key)) {
            return null;
        }
        Hop hop = hops.get(HopKey.of(key, partners.get(key)));
        return hop == null || hop.budget == null ? null : status(hop);
    }

    private static HopStatus status(Hop hop) {
        return new HopStatus(BlockPos.of(hop.a), BlockPos.of(hop.b), hop.budget, hop.weather);
    }

    /** Whether the graph has been solved since the network last had something to judge. */
    public boolean solved() {
        return solved;
    }

    /** Game time of the last recompute (meaningful once {@link #solved()}). */
    public long lastRecomputeTick() {
        return lastRecomputeTick;
    }

    /** {@code requireBackhaul} as the last recompute applied it (false before the first). */
    public boolean appliedRequireBackhaul() {
        return Boolean.TRUE.equals(appliedRequire);
    }

    /** Recomputes so far, hops marched by the last one, and its wall time (game tests, status). */
    public long recomputes() {
        return recomputes;
    }

    public int lastMarched() {
        return lastMarched;
    }

    public long lastRecomputeNanos() {
        return lastRecomputeNanos;
    }

    // ---- the recompute ----------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            BackhaulNetwork network = peek(level);
            if (network != null) {
                network.tick(level, level.getGameTime());
            }
        }
        if (server.getTickCount() % LENS_SEND_INTERVAL_TICKS == 0) {
            sendLensLinks(server);
        }
    }

    /**
     * Once per server tick: recomputes if a trigger fired and the last recompute is at least
     * {@code backhaulRecomputeTicks} old (class javadoc). Public for the game tests, which drive it
     * directly to time it.
     */
    public void tick(ServerLevel level, long gameTime) {
        boolean required = RanCraftConfig.requireBackhaul();
        if (!required && cores.isEmpty() && dishes.isEmpty()) {
            // Nothing to judge and nothing for backhaul to do: drop any old result. If requireBackhaul
            // was just turned off, every cell it held off the air comes back.
            boolean wasRequired = Boolean.TRUE.equals(appliedRequire);
            if (solved || !hops.isEmpty()) {
                hops.clear();
                cellStates = Map.of();
                dishStates = Map.of();
                solved = false;
            }
            appliedRequire = Boolean.FALSE;
            if (wasRequired) {
                refreshCells(level, new ArrayList<>(cells.keySet()));
            }
            return;
        }

        if (solved && gameTime - lastRecomputeTick < RanCraftConfig.backhaulRecomputeTicks()
                && gameTime >= lastRecomputeTick) {
            return;
        }

        RfConfig config = RanCraftConfig.snapshot();
        MicrowaveLink link = RfDataLoader.microwave();
        MaterialTable materials = RfDataLoader.materials();
        boolean nowRaining = level.isRaining();
        boolean nowThundering = level.isThundering();
        long siteVersion = SiteRegistry.of(level).version();
        RegionEpochs epochs = RegionEpochs.of(level);

        // A datapack reload (new link figures or a new material table), a new metersPerBlock or a new
        // range: march every hop again.
        boolean figuresChanged = link != measuredWith || materials != measuredMaterials
                || config.metersPerBlock() != measuredMetersPerBlock
                || config.maxEvaluationRangeBlocks() != measuredRangeBlocks;
        boolean weatherChanged = nowRaining != raining || nowThundering != thundering
                || config.enableRainFade() != rainFade;
        boolean topologyChanged = topologyVersion != solvedTopologyVersion || siteVersion != solvedSiteVersion
                || !config.backhaulTopology().equals(solvedTopology)
                || !Boolean.valueOf(required).equals(appliedRequire);
        boolean epochMoved = false;
        long bumps = epochs.bumps();
        if (bumps != checkedBumps) {
            for (Hop hop : hops.values()) {
                if (!epochs.unchanged(hop.dependencies)) {
                    epochMoved = true;
                    break;
                }
            }
            if (!epochMoved) {
                checkedBumps = bumps;
            }
        }
        if (solved && !figuresChanged && !weatherChanged && !topologyChanged && !epochMoved) {
            return;
        }
        recompute(level, gameTime, config, link, materials, required, figuresChanged, nowRaining, nowThundering,
                epochs);
    }

    /**
     * Recomputes at once, whatever the triggers and the interval say; {@code remeasure} marches every
     * hop again too. For the game tests, which time the recompute (NOTES.md, slice 12, measured), and
     * which need a result now rather than at the next server tick.
     *
     * @return the recompute's wall time, in nanoseconds.
     */
    public long recomputeNow(ServerLevel level, boolean remeasure) {
        recompute(level, level.getGameTime(), RanCraftConfig.snapshot(), RfDataLoader.microwave(),
                RfDataLoader.materials(), RanCraftConfig.requireBackhaul(), remeasure, level.isRaining(),
                level.isThundering(), RegionEpochs.of(level));
        return lastRecomputeNanos;
    }

    private void recompute(ServerLevel level, long gameTime, RfConfig config, MicrowaveLink link,
                           MaterialTable materials, boolean required, boolean reMarchAll,
                           boolean nowRaining, boolean nowThundering, RegionEpochs epochs) {
        long start = System.nanoTime();
        raining = nowRaining;
        thundering = nowThundering;
        rainFade = config.enableRainFade();
        measuredWith = link;
        measuredMaterials = materials;
        measuredMetersPerBlock = config.metersPerBlock();
        measuredRangeBlocks = config.maxEvaluationRangeBlocks();
        checkedBumps = epochs.bumps();

        // 1. Hops: one per mutual pair of dishes. New ones and those whose bins moved are marched.
        Map<HopKey, Hop> wanted = new LinkedHashMap<>();
        for (long dish : dishes) {
            if (!partners.containsKey(dish)) {
                continue;
            }
            long partner = partners.get(dish);
            if (dish < partner && dishes.contains(partner) && partners.containsKey(partner)
                    && partners.get(partner) == dish) {
                HopKey key = new HopKey(dish, partner);
                Hop hop = hops.get(key);
                wanted.put(key, hop != null ? hop : new Hop(dish, partner));
            }
        }
        hops.clear();
        hops.putAll(wanted);

        int marched = 0;
        LevelWorldProbe probe = null;
        for (Hop hop : hops.values()) {
            if (hop.clear == null || reMarchAll || !epochs.unchanged(hop.dependencies)) {
                if (probe == null) {
                    probe = new LevelWorldProbe(level, materials);
                }
                measure(level, hop, link, config, probe, epochs);
                marched++;
            }
            hop.weather = MicrowaveLink.Weather.at(nowRaining, nowThundering, hop.precipitation);
            hop.budget = link.withWeather(hop.clear, config, hop.weather);
        }

        // 2. The graph.
        List<BackhaulGraph.Node> coreNodes = new ArrayList<>(cores.size());
        for (long core : cores) {
            coreNodes.add(node(core));
        }
        List<BackhaulGraph.Node> dishNodes = new ArrayList<>(dishes.size());
        for (long dish : dishes) {
            dishNodes.add(node(dish));
        }
        List<BackhaulGraph.Link> links = new ArrayList<>(hops.size());
        for (Hop hop : hops.values()) {
            links.add(new BackhaulGraph.Link(hop.a, hop.b, hop.budget.state()));
        }
        BackhaulGraph.Topology topology = config.backhaulTopology();
        BackhaulGraph.Result result = BackhaulGraph.solve(topology, coreNodes, cells.values(), dishNodes, links);

        // 3. Effects. Only with requireBackhaul on (or just switched) can a state change move a cell on
        // or off the air, so only then are antennas refreshed: with it off, nothing a cell does changes.
        Map<Long, BackhaulState> previous = cellStates;
        boolean requireFlipped = !Boolean.valueOf(required).equals(appliedRequire);
        cellStates = result.cells();
        dishStates = result.dishes();
        appliedRequire = required;
        solved = true;
        solvedTopology = topology;
        solvedTopologyVersion = topologyVersion;
        lastRecomputeTick = gameTime;
        recomputes++;
        lastMarched = marched;

        if (required || requireFlipped) {
            List<Long> refresh = new ArrayList<>();
            for (long cellId : cells.keySet()) {
                if (requireFlipped || previous.get(cellId) != cellStates.get(cellId)) {
                    refresh.add(cellId);
                }
            }
            refreshCells(level, refresh);
        }
        // After the refresh: the registry changes it made are this recompute's own, not a new trigger.
        // (A refresh may also note a cell anew, which moves topologyVersion: that is a real change.)
        solvedSiteVersion = SiteRegistry.of(level).version();
        lastRecomputeNanos = System.nanoTime() - start;
    }

    /**
     * Marches one hop between the two dish centres, in the fixed order (lower packed position first),
     * with a step cap no march of it can run out of ({@link MicrowaveLink#stepsToReach}). A hop longer
     * than {@code maxEvaluationRangeBlocks} is not marched: it is DOWN, out of range (class javadoc).
     * Reads the biome's precipitation at the midpoint and snapshots the hop's dependency bins.
     */
    private static void measure(ServerLevel level, Hop hop, MicrowaveLink link, RfConfig config,
                                LevelWorldProbe probe, RegionEpochs epochs) {
        double metersPerBlock = config.metersPerBlock();
        double ax = BlockPos.getX(hop.a) + 0.5;
        double ay = BlockPos.getY(hop.a) + 0.5;
        double az = BlockPos.getZ(hop.a) + 0.5;
        double bx = BlockPos.getX(hop.b) + 0.5;
        double by = BlockPos.getY(hop.b) + 0.5;
        double bz = BlockPos.getZ(hop.b) + 0.5;
        double lengthBlocks = Math.sqrt(distanceSq(hop.a, hop.b));
        // maxSteps 0: the march stops before its first voxel, so the hop is out of range at no cost.
        int steps = lengthBlocks > config.maxEvaluationRangeBlocks()
                ? 0
                : link.stepsToReach(ax, ay, az, bx, by, bz, metersPerBlock);
        hop.clear = link.evaluate(probe, ax, ay, az, bx, by, bz, metersPerBlock, MicrowaveLink.Weather.CLEAR, steps);
        BlockPos midpoint = BlockPos.containing((ax + bx) * 0.5, (ay + by) * 0.5, (az + bz) * 0.5);
        hop.precipitation = precipitationAt(level, midpoint);
        // No block event can fire between the march and this snapshot (one thread).
        hop.dependencies = steps == 0
                ? RegionEpochs.Snapshot.NONE
                : epochs.snapshot(link.dependencyBins(ax, ay, az, bx, by, bz, metersPerBlock, RegionEpochs.BIN_SIZE));
    }

    /** Squared distance between two packed block positions, in blocks. */
    public static double distanceSq(long a, long b) {
        double dx = BlockPos.getX(b) - BlockPos.getX(a);
        double dy = BlockPos.getY(b) - BlockPos.getY(a);
        double dz = BlockPos.getZ(b) - BlockPos.getZ(a);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * What the biome at {@code pos} makes of rain ({@code Biome.getPrecipitationAt}): NONE as CLEAR,
     * RAIN, SNOW. The biome is the 4 x 4 x 4 biome cell's, from the chunk when it is loaded and from the
     * world generator otherwise: never a chunk load or a wait (class javadoc).
     */
    public static MicrowaveLink.Weather precipitationAt(ServerLevel level, BlockPos pos) {
        int qx = QuartPos.fromBlock(pos.getX());
        int qy = QuartPos.fromBlock(pos.getY());
        int qz = QuartPos.fromBlock(pos.getZ());
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        Holder<Biome> biome = chunk != null ? chunk.getNoiseBiome(qx, qy, qz) : level.getUncachedNoiseBiome(qx, qy, qz);
        return switch (biome.value().getPrecipitationAt(pos)) {
            case NONE -> MicrowaveLink.Weather.CLEAR;
            case RAIN -> MicrowaveLink.Weather.RAIN;
            case SNOW -> MicrowaveLink.Weather.SNOW;
        };
    }

    private static BackhaulGraph.Node node(long pos) {
        return new BackhaulGraph.Node(pos, BlockPos.getX(pos), BlockPos.getZ(pos));
    }

    /** Asks each cell's antenna (when its chunk is loaded) to refresh its registration. */
    private static void refreshCells(ServerLevel level, List<Long> cellIds) {
        for (long cellId : cellIds) {
            BlockPos pos = BlockPos.of(cellId);
            LevelChunk chunk = level.getChunkSource().getChunkNow(
                    SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
            if (chunk != null && chunk.getBlockEntity(pos) instanceof AntennaBlockEntity antenna) {
                antenna.refreshRegistration();
            }
        }
    }

    // ---- chunk validation -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        BackhaulNetwork network = peek(level);
        if (network != null) {
            network.validate(level, chunk);
        }
    }

    // ---- the lens ---------------------------------------------------------------------------------

    /**
     * Sends each player wearing an RF Lens with the lobes layer on the hops within the server's view
     * distance of them (what the client could see anyway), nearest first, at most
     * {@link BackhaulLinksPayload#MAX_LINKS}; only when that set changed. A player whose last payload
     * had hops and who now gets none (lens off, out of range, hops gone) is sent an empty one, once.
     */
    private static void sendLensLinks(MinecraftServer server) {
        double range = Math.max(1, server.getPlayerList().getViewDistance()) * 16.0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack lens = RfLensItem.wornBy(player);
            BackhaulNetwork network = lens != null && RfLensItem.settingsOf(lens).showLobes()
                    ? peek(player.serverLevel()) : null;
            List<BackhaulLinksPayload.Link> links = network == null
                    ? List.of()
                    : network.linksNear(player.getX(), player.getEyeY(), player.getZ(), range,
                            BackhaulLinksPayload.MAX_LINKS);
            List<BackhaulLinksPayload.Link> last = LENS_SENT.get(player.getUUID());
            if (links.isEmpty()) {
                if (last != null && !last.isEmpty()) {
                    PacketDistributor.sendToPlayer(player, BackhaulLinksPayload.empty());
                }
                LENS_SENT.remove(player.getUUID());
                continue;
            }
            if (links.equals(last)) {
                continue;
            }
            LENS_SENT.put(player.getUUID(), links);
            PacketDistributor.sendToPlayer(player, new BackhaulLinksPayload(links));
        }
    }

    /** The hops passing within {@code range} of a point, nearest first, at most {@code max}. */
    public List<BackhaulLinksPayload.Link> linksNear(double x, double y, double z, double range, int max) {
        double rangeSq = range * range;
        List<HopStatus> near = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (HopStatus hop : hops()) {
            double d = distanceSqToHop(x, y, z, hop);
            if (d <= rangeSq) {
                near.add(hop);
                distances.add(d);
            }
        }
        Integer[] order = new Integer[near.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(distances::get));
        List<BackhaulLinksPayload.Link> links = new ArrayList<>(Math.min(max, order.length));
        for (int i = 0; i < order.length && links.size() < max; i++) {
            HopStatus hop = near.get(order[i]);
            MicrowaveLink.Budget budget = hop.budget();
            links.add(new BackhaulLinksPayload.Link(
                    hop.a().getX(), hop.a().getY(), hop.a().getZ(),
                    hop.b().getX(), hop.b().getY(), hop.b().getZ(),
                    budget.state(), finiteFloat(budget.rslDbm()), finiteFloat(budget.marginDb())));
        }
        return links;
    }

    /** Squared distance from a point to a hop's line between the two dish centres. */
    public static double distanceSqToHop(double x, double y, double z, HopStatus hop) {
        return distanceSqToSegment(x, y, z,
                hop.a().getX() + 0.5, hop.a().getY() + 0.5, hop.a().getZ() + 0.5,
                hop.b().getX() + 0.5, hop.b().getY() + 0.5, hop.b().getZ() + 0.5);
    }

    /** -Infinity (an out-of-range hop) as the most negative float, so a label can still print it. */
    static float finiteFloat(double value) {
        return Double.isFinite(value) ? (float) value : -Float.MAX_VALUE;
    }

    /** Squared distance from a point to the segment from A to B. Pure. */
    static double distanceSqToSegment(double px, double py, double pz,
                                      double ax, double ay, double az,
                                      double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double lengthSq = dx * dx + dy * dy + dz * dz;
        double t = lengthSq == 0.0 ? 0.0 : ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / lengthSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = ax + dx * t - px;
        double cy = ay + dy * t - py;
        double cz = az + dz * t - pz;
        return cx * cx + cy * cy + cz * cz;
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LENS_SENT.remove(event.getEntity().getUUID());
    }

    /** The client clears its lens state on a dimension change; send it the new dimension's hops afresh. */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        LENS_SENT.remove(event.getEntity().getUUID());
    }

    /** The client clears its lens state on respawn too ({@code ClientEvents.onClone}). */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        LENS_SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LENS_SENT.clear();
    }
}
