package dev.rancraft.rf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which cells have a path to the core network, and how good it is (Phase 3 slice 11, §3C.2).
 *
 * <p>One graph per dimension: the caller passes the cores, cells and dishes of one dimension only,
 * so "a core in the same dimension" holds by construction. Its vertices are the core network, every
 * cell (by its column base) and every Backhaul Dish. Edges:
 * <ul>
 *   <li><b>Fiber:</b> a cell's base, or a dish, within {@link Topology#fiberRadiusBlocks()}
 *       horizontally of any core is connected to the core network.</li>
 *   <li><b>Site:</b> a dish within {@link Topology#siteRadiusBlocks()} horizontally of a cell's base
 *       serves that cell. The cell's site is a switching point: two dishes serving the same cell are
 *       connected through it, so a site with an incoming and an outgoing hop relays (the chained
 *       sites of §3C.2's done-when). A site with no cell relays nothing.</li>
 *   <li><b>Microwave:</b> a paired dish hop, with its measured {@link MicrowaveLink.LinkState}.</li>
 * </ul>
 *
 * Two breadth-first passes from the core network:
 * <ol>
 *   <li>fiber, site and UP hops only: every cell reached is {@link BackhaulState#FULL};</li>
 *   <li>DEGRADED hops added: every cell newly reached is {@link BackhaulState#LIMITED};</li>
 *   <li>every other cell is {@link BackhaulState#NONE}.</li>
 * </ol>
 * So a cell with both an UP path and a DEGRADED one is FULL: the UP path wins. A DOWN hop carries
 * nothing.
 *
 * <p><b>Game abstractions, labelled (NOTES.md, Phase 3 slice 11):</b>
 * <ul>
 *   <li><b>Fiber is implicit, by radius.</b> Nothing is laid: being near a core is being on fiber.
 *       Real fiber is trenched or strung on poles, has its own cuts and its own capacity.</li>
 *   <li><b>Site cabling is implicit too, and so is the site router.</b> A dish near a cell's base
 *       feeds it, and the cell's site switches between its dishes, with no indoor unit, cable or
 *       router to place.</li>
 *   <li><b>Both radii are horizontal.</b> §3C.2 says so for fiber; for the site radius it is this
 *       class's choice, so a dish on top of a tall mast column (up to {@code maxMastHeight} above its
 *       base) still serves the column's own cell, as a real dish on a tower serves the antennas below it.</li>
 *   <li><b>Capacity is two levels.</b> FULL or LIMITED is the worst hop's modulation state along the
 *       best path, not a throughput: there is no link capacity, no traffic and no sharing of a hop
 *       between the cells behind it.</li>
 * </ul>
 *
 * <p>Cost: cells and dishes are bucketed into squares one radius wide, so each core is compared only
 * with the cells and dishes, and each dish only with the cells, in the 3 × 3 squares around it:
 * roughly linear in cells + dishes + links (NOTES.md, slice 11, has the measured figures). It runs at
 * most once per {@code backhaulRecomputeTicks}.
 *
 * <p>Pure: no Minecraft imports ({@code PackagePurityTest}).
 */
public final class BackhaulGraph {

    private BackhaulGraph() {
    }

    /**
     * The ceiling a {@link BackhaulState#LIMITED} cell puts on the service level of every device it
     * serves (§3C.2, Phase 3 slice 12). <b>Game abstraction, labelled (§6, "the backhaul cap as a flat
     * FAIR ceiling"):</b> a real backhaul-limited cell runs out of transport capacity, so its users'
     * throughput drops as the load grows, and a lightly loaded one may not notice. Here there is no
     * traffic and no load, so the limit is one flat step: whatever the radio link, the device gets at
     * most FAIR. The signal itself is not touched (the {@link SignalSample} stays pure RF); the cap is
     * applied in the network layer, in the device's context.
     */
    public static final ServiceLevel LIMITED_SERVICE_CAP = ServiceLevel.FAIR;

    /**
     * The service cap a cell's backhaul puts on its devices (Phase 3 slice 12): {@link #LIMITED_SERVICE_CAP}
     * for LIMITED, {@link ServiceLevel#NONE} for NONE (a cell with no backhaul carries nothing; with
     * {@code requireBackhaul} on it is off the air anyway), and no cap ({@link ServiceLevel#EXCELLENT})
     * for FULL or an unknown state ({@code null}: not solved yet).
     *
     * <p><b>Only with {@code requireBackhaul} on.</b> With it off (the default) backhaul has no effect
     * at all: a cell with no backhaul transmits at full service, so a LIMITED one cannot be capped
     * either, and a Phase 2 world plays exactly as before (NOTES.md, slice 12, decision 1).
     */
    public static ServiceLevel serviceCap(BackhaulState state, boolean requireBackhaul) {
        if (!requireBackhaul || state == null) {
            return ServiceLevel.EXCELLENT;
        }
        return switch (state) {
            case FULL -> ServiceLevel.EXCELLENT;
            case LIMITED -> LIMITED_SERVICE_CAP;
            case NONE -> ServiceLevel.NONE;
        };
    }

    /**
     * Whether a cell with this backhaul may be on the air (§3C.2: "NONE and requireBackhaul →
     * isTransmitting() is false"). An unknown state ({@code null}: the cell is newer than the last
     * solve) counts as on the air until the next solve says otherwise, so a world loading chunk by
     * chunk does not take every cell off the air and back (NOTES.md, slice 12, decision 3).
     */
    public static boolean allowsOnAir(BackhaulState state, boolean requireBackhaul) {
        return !requireBackhaul || state != BackhaulState.NONE;
    }

    /** A cell's backhaul, as the meter shows it ("BH: LIMITED"). */
    public enum BackhaulState {
        /** Reached over fiber and UP hops only. */
        FULL,
        /** Reached only through at least one DEGRADED hop: the cell is backhaul-limited. */
        LIMITED,
        /** Not reached: no path to the core network. */
        NONE
    }

    /**
     * The two topology radii (§5: {@code fiberRadiusBlocks} 24, {@code siteRadiusBlocks} 8), both
     * horizontal, in blocks, inclusive.
     */
    public record Topology(double fiberRadiusBlocks, double siteRadiusBlocks) {

        public static final double DEFAULT_FIBER_RADIUS_BLOCKS = 24.0;
        public static final double DEFAULT_SITE_RADIUS_BLOCKS = 8.0;
        public static final Topology DEFAULT =
                new Topology(DEFAULT_FIBER_RADIUS_BLOCKS, DEFAULT_SITE_RADIUS_BLOCKS);

        public Topology {
            if (!(fiberRadiusBlocks >= 0.0) || !Double.isFinite(fiberRadiusBlocks)) {
                throw new IllegalArgumentException("fiberRadiusBlocks must be zero or positive: " + fiberRadiusBlocks);
            }
            if (!(siteRadiusBlocks >= 0.0) || !Double.isFinite(siteRadiusBlocks)) {
                throw new IllegalArgumentException("siteRadiusBlocks must be zero or positive: " + siteRadiusBlocks);
            }
        }

        /** Whether {@code node} is on fiber from {@code core}: within the fiber radius horizontally. */
        public boolean onFiber(Node node, Node core) {
            return withinHorizontally(node, core, fiberRadiusBlocks);
        }

        /** Whether {@code dish} serves the cell whose base is {@code cellBase}. */
        public boolean onSite(Node dish, Node cellBase) {
            return withinHorizontally(dish, cellBase, siteRadiusBlocks);
        }

        private static boolean withinHorizontally(Node a, Node b, double radius) {
            double dx = (double) a.x() - b.x();
            double dz = (double) a.z() - b.z();
            return dx * dx + dz * dz <= radius * radius;
        }
    }

    /**
     * A core, a cell's base or a dish: the caller's key and its block column. A cell's id is its cell id
     * (the column base's packed position); a dish's is its own packed position. Height is not part of
     * the topology (both radii are horizontal).
     */
    public record Node(long id, int x, int z) {
    }

    /** A paired hop between two dishes, by their ids, with its measured state. Undirected. */
    public record Link(long dishA, long dishB, MicrowaveLink.LinkState state) {
    }

    /**
     * Per-cell and per-dish backhaul, in the order the cells and dishes were given. Every cell and
     * dish passed in has an entry.
     */
    public record Result(Map<Long, BackhaulState> cells, Map<Long, BackhaulState> dishes) {

        /** A cell's backhaul; NONE for an id that was not passed in. */
        public BackhaulState cell(long cellId) {
            return cells.getOrDefault(cellId, BackhaulState.NONE);
        }

        /** A dish's own reach to the core (the same rule as a cell's); NONE for an unknown id. */
        public BackhaulState dish(long dishId) {
            return dishes.getOrDefault(dishId, BackhaulState.NONE);
        }
    }

    /**
     * Solves one dimension's backhaul (class javadoc).
     *
     * <p>Ids are unique per kind: a repeated cell id or dish id keeps its first entry. A link naming a
     * dish that was not passed in, or joining a dish to itself, is ignored, as is a DOWN link; the same
     * pair given twice counts with its better state.
     */
    public static Result solve(Topology topology,
                               Collection<Node> cores,
                               Collection<Node> cells,
                               Collection<Node> dishes,
                               Collection<Link> links) {
        // Vertices: cells first, then dishes.
        List<Node> nodes = new ArrayList<>(cells.size() + dishes.size());
        Map<Long, Integer> cellIndex = new LinkedHashMap<>();
        for (Node cell : cells) {
            if (cellIndex.putIfAbsent(cell.id(), nodes.size()) == null) {
                nodes.add(cell);
            }
        }
        int cellCount = nodes.size();
        Map<Long, Integer> dishIndex = new LinkedHashMap<>();
        for (Node dish : dishes) {
            if (dishIndex.putIfAbsent(dish.id(), nodes.size()) == null) {
                nodes.add(dish);
            }
        }
        int n = nodes.size();

        // Fiber: the vertices adjacent to the core network. Cores are few, so each core looks up the
        // vertices around it.
        boolean[] onFiber = new boolean[n];
        if (!cores.isEmpty()) {
            Grid nodeGrid = new Grid(topology.fiberRadiusBlocks());
            for (int i = 0; i < n; i++) {
                nodeGrid.add(i, nodes.get(i));
            }
            for (Node core : cores) {
                for (int i : nodeGrid.near(core)) {
                    if (!onFiber[i] && topology.onFiber(nodes.get(i), core)) {
                        onFiber[i] = true;
                    }
                }
            }
        }

        // Edges with the pass from which they may be used: 0 = site and UP (both passes), 1 = DEGRADED.
        List<List<int[]>> adjacency = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacency.add(new ArrayList<>());
        }
        Grid cellGrid = new Grid(topology.siteRadiusBlocks());
        for (int c = 0; c < cellCount; c++) {
            cellGrid.add(c, nodes.get(c));
        }
        for (int d = cellCount; d < n; d++) {
            Node dish = nodes.get(d);
            for (int c : cellGrid.near(dish)) {
                if (topology.onSite(dish, nodes.get(c))) {
                    addEdge(adjacency, d, c, 0);
                }
            }
        }
        for (Link link : links) {
            Integer a = dishIndex.get(link.dishA());
            Integer b = dishIndex.get(link.dishB());
            if (a == null || b == null || a.equals(b)) {
                continue;
            }
            switch (link.state()) {
                case UP -> addEdge(adjacency, a, b, 0);
                case DEGRADED -> addEdge(adjacency, a, b, 1);
                case DOWN -> {
                    // carries nothing
                }
            }
        }

        boolean[] full = reach(adjacency, onFiber, 0);
        boolean[] limited = reach(adjacency, onFiber, 1);

        Map<Long, BackhaulState> cellStates = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : cellIndex.entrySet()) {
            cellStates.put(entry.getKey(), stateOf(full, limited, entry.getValue()));
        }
        Map<Long, BackhaulState> dishStates = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : dishIndex.entrySet()) {
            dishStates.put(entry.getKey(), stateOf(full, limited, entry.getValue()));
        }
        return new Result(Collections.unmodifiableMap(cellStates), Collections.unmodifiableMap(dishStates));
    }

    /**
     * Indices bucketed by block column into squares one radius wide (at least 1 block), so the nodes
     * within that radius of any point are among the 3 x 3 squares around it. Only a candidate list: the
     * caller still applies the exact distance test.
     */
    private static final class Grid {

        private static final List<Integer> NONE = List.of();

        private final int size;
        private final Map<Long, List<Integer>> buckets = new HashMap<>();

        Grid(double radius) {
            // At least the radius, so a node within it is at most one square away. A radius past the
            // int range makes every int column fall in squares -2..0, which one 3 x 3 look covers.
            this.size = (int) Math.max(1.0, Math.min(Math.ceil(radius), Integer.MAX_VALUE));
        }

        void add(int index, Node node) {
            buckets.computeIfAbsent(key(Math.floorDiv(node.x(), size), Math.floorDiv(node.z(), size)),
                    k -> new ArrayList<>()).add(index);
        }

        /** Every index in the 3 x 3 squares around {@code centre}'s square. */
        List<Integer> near(Node centre) {
            if (buckets.isEmpty()) {
                return NONE;
            }
            // long, so that a square at the edge of the int range cannot overflow the loop.
            long bx = Math.floorDiv(centre.x(), size);
            long bz = Math.floorDiv(centre.z(), size);
            List<Integer> found = null;
            for (long ix = bx - 1; ix <= bx + 1; ix++) {
                for (long iz = bz - 1; iz <= bz + 1; iz++) {
                    List<Integer> bucket = buckets.get(key(ix, iz));
                    if (bucket != null) {
                        if (found == null) {
                            found = new ArrayList<>(bucket);
                        } else {
                            found.addAll(bucket);
                        }
                    }
                }
            }
            return found == null ? NONE : found;
        }

        /**
         * Squares of real nodes are ints; a square one past the int range can collide with one at the
         * other end, which only adds candidates that the caller's exact test then rejects.
         */
        private static long key(long bx, long bz) {
            return (bx << 32) ^ (bz & 0xFFFFFFFFL);
        }
    }

    private static void addEdge(List<List<int[]>> adjacency, int a, int b, int pass) {
        adjacency.get(a).add(new int[] {b, pass});
        adjacency.get(b).add(new int[] {a, pass});
    }

    /** Breadth-first from every fiber-connected vertex over the edges usable in {@code pass}. */
    private static boolean[] reach(List<List<int[]>> adjacency, boolean[] seeds, int pass) {
        int n = seeds.length;
        boolean[] reached = new boolean[n];
        int[] queue = new int[n];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < n; i++) {
            if (seeds[i]) {
                reached[i] = true;
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int current = queue[head++];
            for (int[] edge : adjacency.get(current)) {
                int next = edge[0];
                if (edge[1] <= pass && !reached[next]) {
                    reached[next] = true;
                    queue[tail++] = next;
                }
            }
        }
        return reached;
    }

    private static BackhaulState stateOf(boolean[] full, boolean[] limited, int index) {
        if (full[index]) {
            return BackhaulState.FULL;
        }
        return limited[index] ? BackhaulState.LIMITED : BackhaulState.NONE;
    }
}
