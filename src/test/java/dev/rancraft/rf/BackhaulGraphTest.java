package dev.rancraft.rf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.rancraft.rf.BackhaulGraph.BackhaulState;
import dev.rancraft.rf.BackhaulGraph.Link;
import dev.rancraft.rf.BackhaulGraph.Node;
import dev.rancraft.rf.BackhaulGraph.Result;
import dev.rancraft.rf.BackhaulGraph.Topology;
import dev.rancraft.rf.MicrowaveLink.LinkState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 slice 11 (§3C.2, 3C tests): the backhaul graph's two passes.
 *
 * <p>The chain used throughout: a core at the origin with dish {@code D0} 10 blocks away (on fiber),
 * then three sites 200 blocks apart along x, each a cell with one dish facing back towards the core
 * and one facing on (the last has only the first):
 *
 * <pre>
 * core ─fiber─ D0 ~~~ D1a ─site─ C1 ─site─ D1b ~~~ D2a ─site─ C2 ─site─ D2b ~~~ D3a ─site─ C3
 * </pre>
 */
class BackhaulGraphTest {

    private static final Topology TOPOLOGY = Topology.DEFAULT;

    private static final Node CORE = new Node(1L, 0, 0);

    private static final Node C1 = new Node(101L, 200, 0);
    private static final Node C2 = new Node(102L, 400, 0);
    private static final Node C3 = new Node(103L, 600, 0);

    private static final Node D0 = new Node(200L, 10, 0);
    private static final Node D1A = new Node(211L, 203, 0);
    private static final Node D1B = new Node(212L, 205, 3);
    private static final Node D2A = new Node(221L, 402, 0);
    private static final Node D2B = new Node(222L, 404, -2);
    private static final Node D3A = new Node(231L, 601, 1);

    private static Result chain(LinkState first, LinkState middle, LinkState last) {
        return BackhaulGraph.solve(TOPOLOGY, List.of(CORE), List.of(C1, C2, C3),
                List.of(D0, D1A, D1B, D2A, D2B, D3A),
                List.of(new Link(D0.id(), D1A.id(), first),
                        new Link(D1B.id(), D2A.id(), middle),
                        new Link(D2B.id(), D3A.id(), last)));
    }

    @Test
    @DisplayName("FULL via fiber: a cell base within fiberRadiusBlocks horizontally of a core, the bound inclusive")
    void fullViaFiber() {
        Node atRadius = new Node(10L, 24, 0);
        Node diagonal = new Node(11L, -16, 17);   // 23.3 blocks
        Node justOutside = new Node(12L, 17, 17); // 24.04 blocks
        Node farther = new Node(13L, 0, -25);
        Result result = BackhaulGraph.solve(TOPOLOGY, List.of(CORE), List.of(atRadius, diagonal, justOutside, farther),
                List.of(), List.of());
        assertSame(BackhaulState.FULL, result.cell(10L));
        assertSame(BackhaulState.FULL, result.cell(11L));
        assertSame(BackhaulState.NONE, result.cell(12L));
        assertSame(BackhaulState.NONE, result.cell(13L));
    }

    @Test
    @DisplayName("FULL via an UP chain: three sites relayed through their cells' sites")
    void fullViaUpChain() {
        Result result = chain(LinkState.UP, LinkState.UP, LinkState.UP);
        assertSame(BackhaulState.FULL, result.cell(C1.id()));
        assertSame(BackhaulState.FULL, result.cell(C2.id()));
        assertSame(BackhaulState.FULL, result.cell(C3.id()));
        assertSame(BackhaulState.FULL, result.dish(D3A.id()));
    }

    @Test
    @DisplayName("LIMITED via one DEGRADED hop: the cells behind it, not the ones before it")
    void limitedViaOneDegradedHop() {
        Result result = chain(LinkState.UP, LinkState.DEGRADED, LinkState.UP);
        assertSame(BackhaulState.FULL, result.cell(C1.id()));
        assertSame(BackhaulState.LIMITED, result.cell(C2.id()));
        assertSame(BackhaulState.LIMITED, result.cell(C3.id()));
        assertSame(BackhaulState.FULL, result.dish(D1B.id()));
        assertSame(BackhaulState.LIMITED, result.dish(D2A.id()));
    }

    @Test
    @DisplayName("NONE when isolated: no fiber, no dish, or only a DOWN hop")
    void noneWhenIsolated() {
        Node lonely = new Node(150L, 5000, 5000);
        Result alone = BackhaulGraph.solve(TOPOLOGY, List.of(CORE), List.of(lonely), List.of(), List.of());
        assertSame(BackhaulState.NONE, alone.cell(lonely.id()));

        Result down = chain(LinkState.UP, LinkState.UP, LinkState.DOWN);
        assertSame(BackhaulState.FULL, down.cell(C2.id()));
        assertSame(BackhaulState.NONE, down.cell(C3.id()));
        assertSame(BackhaulState.NONE, down.dish(D3A.id()));

        Result noCore = BackhaulGraph.solve(TOPOLOGY, List.of(), List.of(C1, C2, C3),
                List.of(D0, D1A, D1B, D2A, D2B, D3A),
                List.of(new Link(D0.id(), D1A.id(), LinkState.UP)));
        assertSame(BackhaulState.NONE, noCore.cell(C1.id()));
        // An id that was never passed in.
        assertSame(BackhaulState.NONE, noCore.cell(999L));
    }

    @Test
    @DisplayName("a DEGRADED path loses to an UP path when both exist")
    void upPathBeatsDegradedPath() {
        // C2 hears the chain's DEGRADED middle hop and a second, UP route from another dish at the core.
        Node d0b = new Node(201L, 12, 5);
        Node d2c = new Node(223L, 397, 4);
        List<Link> links = new ArrayList<>(List.of(
                new Link(D0.id(), D1A.id(), LinkState.UP),
                new Link(D1B.id(), D2A.id(), LinkState.DEGRADED),
                new Link(D2B.id(), D3A.id(), LinkState.UP),
                new Link(d0b.id(), d2c.id(), LinkState.UP)));
        Result result = BackhaulGraph.solve(TOPOLOGY, List.of(CORE), List.of(C1, C2, C3),
                List.of(D0, d0b, D1A, D1B, D2A, D2B, d2c, D3A), links);
        assertSame(BackhaulState.FULL, result.cell(C2.id()));
        assertSame(BackhaulState.FULL, result.cell(C3.id()));
        assertSame(BackhaulState.FULL, result.dish(D2A.id()), "reached the UP way round, through C2's site");

        // The same pair given twice, DEGRADED then UP, counts as UP.
        Result twice = BackhaulGraph.solve(TOPOLOGY, List.of(CORE), List.of(C1), List.of(D0, D1A),
                List.of(new Link(D0.id(), D1A.id(), LinkState.DEGRADED), new Link(D1A.id(), D0.id(), LinkState.UP)));
        assertSame(BackhaulState.FULL, twice.cell(C1.id()));
    }

    @Test
    @DisplayName("breaking the middle site's dish takes the far site off: the done-when, at graph level")
    void breakingTheMiddleDish() {
        // Without D2B, C3's only path is gone; C1 and C2 keep theirs.
        Result result = BackhaulGraph.solve(TOPOLOGY, List.of(CORE), List.of(C1, C2, C3),
                List.of(D0, D1A, D1B, D2A, D3A),
                List.of(new Link(D0.id(), D1A.id(), LinkState.UP),
                        new Link(D1B.id(), D2A.id(), LinkState.UP),
                        new Link(D2B.id(), D3A.id(), LinkState.UP)));
        assertSame(BackhaulState.FULL, result.cell(C1.id()));
        assertSame(BackhaulState.FULL, result.cell(C2.id()));
        assertSame(BackhaulState.NONE, result.cell(C3.id()));
    }

    @Test
    @DisplayName("a dish serves a cell within siteRadiusBlocks horizontally, the bound inclusive")
    void siteRadius() {
        Node cell = new Node(300L, 100, 100);
        Node atRadius = new Node(310L, 100, 108);
        Node outside = new Node(311L, 106, 106); // 8.49 blocks
        Node feed = new Node(320L, 3, 0);
        Result served = BackhaulGraph.solve(TOPOLOGY, List.of(CORE), List.of(cell), List.of(feed, atRadius),
                List.of(new Link(feed.id(), atRadius.id(), LinkState.UP)));
        assertSame(BackhaulState.FULL, served.cell(cell.id()));
        Result notServed = BackhaulGraph.solve(TOPOLOGY, List.of(CORE), List.of(cell), List.of(feed, outside),
                List.of(new Link(feed.id(), outside.id(), LinkState.UP)));
        assertSame(BackhaulState.NONE, notServed.cell(cell.id()));
        assertSame(BackhaulState.FULL, notServed.dish(outside.id()), "the dish itself is reached");
    }

    @Test
    @DisplayName("a dish on fiber serves a cell beyond the fiber radius")
    void dishOnFiberServesItsCell() {
        Node cell = new Node(400L, 30, 0);  // 30 from the core: not on fiber itself
        Node dish = new Node(410L, 23, 0);  // 23 from the core, 7 from the cell
        Result result = BackhaulGraph.solve(TOPOLOGY, List.of(CORE), List.of(cell), List.of(dish), List.of());
        assertSame(BackhaulState.FULL, result.dish(dish.id()));
        assertSame(BackhaulState.FULL, result.cell(cell.id()));
    }

    @Test
    @DisplayName("a site with no cell relays nothing; any one core is enough")
    void relayNeedsACell() {
        // Two dishes side by side with no cell between them do not switch traffic.
        Node relayIn = new Node(500L, 300, 0);
        Node relayOut = new Node(501L, 302, 0);
        Node farDish = new Node(502L, 600, 0);
        Node farCell = new Node(503L, 603, 0);
        Result result = BackhaulGraph.solve(TOPOLOGY, List.of(CORE), List.of(farCell),
                List.of(D0, relayIn, relayOut, farDish),
                List.of(new Link(D0.id(), relayIn.id(), LinkState.UP),
                        new Link(relayOut.id(), farDish.id(), LinkState.UP)));
        assertSame(BackhaulState.FULL, result.dish(relayIn.id()));
        assertSame(BackhaulState.NONE, result.dish(relayOut.id()));
        assertSame(BackhaulState.NONE, result.cell(farCell.id()));

        // A second core next to the far cell puts it on fiber.
        Result twoCores = BackhaulGraph.solve(TOPOLOGY, List.of(CORE, new Node(2L, 610, 10)), List.of(farCell),
                List.of(), List.of());
        assertSame(BackhaulState.FULL, twoCores.cell(farCell.id()));
    }

    @Test
    @DisplayName("links to unknown dishes, self links and repeated ids are ignored; results keep the input order")
    void malformedInput() {
        Result result = BackhaulGraph.solve(TOPOLOGY, List.of(CORE),
                List.of(C1, C2, new Node(C1.id(), 9000, 9000)),
                List.of(D0, D1A, D1A),
                List.of(new Link(D0.id(), 77777L, LinkState.UP),
                        new Link(D1A.id(), D1A.id(), LinkState.UP),
                        new Link(D0.id(), D1A.id(), LinkState.UP)));
        // The repeated C1 keeps its first position, so C1 is served.
        assertSame(BackhaulState.FULL, result.cell(C1.id()));
        assertSame(BackhaulState.NONE, result.cell(C2.id()));
        assertEquals(List.of(C1.id(), C2.id()), List.copyOf(result.cells().keySet()));
        assertEquals(List.of(D0.id(), D1A.id()), List.copyOf(result.dishes().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> result.cells().put(5L, BackhaulState.FULL));
    }

    @Test
    @DisplayName("the radii come from the config (24 and 8 by default) and must not be negative")
    void topologyFromConfig() {
        assertEquals(new Topology(24.0, 8.0), RfConfig.DEFAULTS.backhaulTopology());
        assertEquals(Topology.DEFAULT, RfConfig.DEFAULTS.backhaulTopology());
        assertThrows(IllegalArgumentException.class, () -> new Topology(-1.0, 8.0));
        assertThrows(IllegalArgumentException.class, () -> new Topology(24.0, Double.NaN));
        // A zero fiber radius still connects a cell standing on the core's column.
        Result zero = BackhaulGraph.solve(new Topology(0.0, 0.0), List.of(CORE),
                List.of(new Node(600L, 0, 0), new Node(601L, 1, 0)), List.of(), List.of());
        assertSame(BackhaulState.FULL, zero.cell(600L));
        assertSame(BackhaulState.NONE, zero.cell(601L));
    }

    @Test
    @DisplayName("the bucketed solve equals a brute-force all-pairs reference on random layouts, radius bounds and negative coordinates included")
    void matchesBruteForce() {
        Random random = new Random(2026L);
        for (int trial = 0; trial < 300; trial++) {
            double fiber = random.nextInt(4) == 0 ? random.nextInt(3) : 1 + random.nextInt(40) + (random.nextBoolean() ? 0.5 : 0.0);
            double site = random.nextInt(4) == 0 ? random.nextInt(2) : 1 + random.nextInt(12) + (random.nextBoolean() ? 0.5 : 0.0);
            Topology topology = new Topology(fiber, site);
            int span = 20 + random.nextInt(300);
            List<Node> cores = new ArrayList<>();
            List<Node> cells = new ArrayList<>();
            List<Node> dishes = new ArrayList<>();
            List<Link> links = new ArrayList<>();
            for (int i = 0, k = random.nextInt(4); i < k; i++) {
                cores.add(new Node(i, random.nextInt(2 * span) - span, random.nextInt(2 * span) - span));
            }
            for (int i = 0, k = random.nextInt(40); i < k; i++) {
                cells.add(new Node(1000L + i, random.nextInt(2 * span) - span, random.nextInt(2 * span) - span));
            }
            for (int i = 0, k = random.nextInt(30); i < k; i++) {
                // Half near a cell (some exactly at the site radius), half anywhere.
                if (!cells.isEmpty() && random.nextBoolean()) {
                    Node cell = cells.get(random.nextInt(cells.size()));
                    int r = (int) Math.floor(site);
                    dishes.add(new Node(5000L + i, cell.x() + random.nextInt(2 * r + 3) - r - 1, cell.z() + (random.nextBoolean() ? r : -r)));
                } else {
                    dishes.add(new Node(5000L + i, random.nextInt(2 * span) - span, random.nextInt(2 * span) - span));
                }
            }
            for (int i = 0, k = dishes.isEmpty() ? 0 : random.nextInt(30); i < k; i++) {
                links.add(new Link(dishes.get(random.nextInt(dishes.size())).id(), dishes.get(random.nextInt(dishes.size())).id(),
                        LinkState.values()[random.nextInt(3)]));
            }
            Result solved = BackhaulGraph.solve(topology, cores, cells, dishes, links);
            Result reference = bruteForce(topology, cores, cells, dishes, links);
            assertEquals(reference, solved, "trial " + trial);
        }
    }

    /** The definition, all pairs and no buckets: reached over fiber + site + allowed hops, two passes. */
    private static Result bruteForce(Topology topology, List<Node> cores, List<Node> cells, List<Node> dishes, List<Link> links) {
        List<Node> nodes = new ArrayList<>(cells);
        nodes.addAll(dishes);
        int n = nodes.size();
        Map<Long, BackhaulState> cellStates = new LinkedHashMap<>();
        Map<Long, BackhaulState> dishStates = new LinkedHashMap<>();
        boolean[][] reached = new boolean[2][n];
        for (int pass = 0; pass < 2; pass++) {
            boolean[] r = reached[pass];
            for (int i = 0; i < n; i++) {
                for (Node core : cores) {
                    r[i] |= topology.onFiber(nodes.get(i), core);
                }
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        if (r[i] && !r[j] && connected(topology, nodes, cells.size(), i, j, links, pass)) {
                            r[j] = true;
                            changed = true;
                        }
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            BackhaulState state = reached[0][i] ? BackhaulState.FULL
                    : (reached[1][i] ? BackhaulState.LIMITED : BackhaulState.NONE);
            (i < cells.size() ? cellStates : dishStates).put(nodes.get(i).id(), state);
        }
        return new Result(cellStates, dishStates);
    }

    private static boolean connected(Topology topology, List<Node> nodes, int cellCount, int i, int j,
                                     List<Link> links, int pass) {
        boolean iDish = i >= cellCount;
        boolean jDish = j >= cellCount;
        if (iDish != jDish) {
            return topology.onSite(nodes.get(iDish ? i : j), nodes.get(iDish ? j : i));
        }
        if (!iDish) {
            return false;
        }
        long a = nodes.get(i).id();
        long b = nodes.get(j).id();
        for (Link link : links) {
            boolean pair = (link.dishA() == a && link.dishB() == b) || (link.dishA() == b && link.dishB() == a);
            boolean usable = link.state() == LinkState.UP || (pass == 1 && link.state() == LinkState.DEGRADED);
            if (pair && usable && a != b) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a long chain: one DEGRADED hop anywhere makes everything behind it LIMITED, a DOWN one NONE")
    void longChain() {
        int sites = 40;
        List<Node> cells = new ArrayList<>();
        List<Node> dishes = new ArrayList<>(List.of(D0));
        List<Link> links = new ArrayList<>();
        long previousOut = D0.id();
        for (int i = 1; i <= sites; i++) {
            int x = i * 150;
            cells.add(new Node(1000L + i, x, 0));
            Node in = new Node(2000L + i, x - 2, 0);
            Node out = new Node(3000L + i, x + 2, 0);
            dishes.add(in);
            dishes.add(out);
            LinkState state = i == 25 ? LinkState.DEGRADED : (i == 33 ? LinkState.DOWN : LinkState.UP);
            links.add(new Link(previousOut, in.id(), state));
            previousOut = out.id();
        }
        Result result = BackhaulGraph.solve(TOPOLOGY, List.of(CORE), cells, dishes, links);
        for (int i = 1; i <= sites; i++) {
            BackhaulState expected = i < 25 ? BackhaulState.FULL : (i < 33 ? BackhaulState.LIMITED : BackhaulState.NONE);
            assertSame(expected, result.cell(1000L + i), "site " + i);
        }
    }

    // ---- slice 12: the effects ------------------------------------------------------------------

    @Test
    @DisplayName("slice 12: with requireBackhaul on, LIMITED caps at FAIR, NONE at NONE, FULL and unknown cap nothing")
    void serviceCapWithRequireBackhaul() {
        assertSame(ServiceLevel.FAIR, BackhaulGraph.LIMITED_SERVICE_CAP);
        assertSame(ServiceLevel.EXCELLENT, BackhaulGraph.serviceCap(BackhaulState.FULL, true));
        assertSame(ServiceLevel.FAIR, BackhaulGraph.serviceCap(BackhaulState.LIMITED, true));
        assertSame(ServiceLevel.NONE, BackhaulGraph.serviceCap(BackhaulState.NONE, true));
        assertSame(ServiceLevel.EXCELLENT, BackhaulGraph.serviceCap(null, true), "not judged yet: no cap");
    }

    @Test
    @DisplayName("slice 12: with requireBackhaul off (the default) backhaul caps nothing and keeps nothing off the air")
    void requireBackhaulOffChangesNothing() {
        for (BackhaulState state : new BackhaulState[] {BackhaulState.FULL, BackhaulState.LIMITED, BackhaulState.NONE, null}) {
            assertSame(ServiceLevel.EXCELLENT, BackhaulGraph.serviceCap(state, false), String.valueOf(state));
            assertEquals(true, BackhaulGraph.allowsOnAir(state, false), String.valueOf(state));
        }
    }

    @Test
    @DisplayName("slice 12: with requireBackhaul on only NONE is off the air; LIMITED and unknown cells transmit")
    void onAirWithRequireBackhaul() {
        assertEquals(true, BackhaulGraph.allowsOnAir(BackhaulState.FULL, true));
        assertEquals(true, BackhaulGraph.allowsOnAir(BackhaulState.LIMITED, true));
        assertEquals(false, BackhaulGraph.allowsOnAir(BackhaulState.NONE, true));
        assertEquals(true, BackhaulGraph.allowsOnAir(null, true), "a cell newer than the last solve transmits until it");
    }
}
