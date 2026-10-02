package dev.rancraft.rf;

/**
 * Link budget of one point-to-point microwave backhaul hop between two Backhaul Dishes (Phase 3
 * slice 11, §3C.2). Its parameters are this record's components, loaded from
 * {@code data/rancraft/rf/backhaul/microwave.json} by {@code RfDataLoader}: a file of its own, outside
 * {@code rf/bands/}, so the link's 18 GHz never appears as a cellular band.
 *
 * <pre>
 * FSPL     = 20·log10(f_MHz) − 27.56 + 20·log10(d_m)             free space, n = 2
 * RSL      = Tx + 2·G_dish − FSPL − Obstruction − Fresnel − Rain
 * UP       if RSL ≥ up_threshold_dbm        (−50: full rate, high-order modulation holds)
 * DEGRADED if RSL ≥ degraded_threshold_dbm  (−70: adaptive modulation stepped down)
 * DOWN     otherwise
 * </pre>
 *
 * At the defaults a clear 1000 m hop has FSPL 117.55 dB and RSL −33.55 dBm, 16.45 dB above UP. One
 * stone block on the line costs 12 dB × 3.0 = 36 dB (DEGRADED), two cost 72 dB (DOWN), six leaves
 * 18 dB (DEGRADED). Distance barely matters (doubling it costs 6 dB) and line of sight is everything,
 * which is true of real short microwave hops.
 *
 * <p><b>Real, modelled faithfully:</b> free-space loss (exponent 2: a dish link above the clutter is
 * line of sight, unlike a cellular link's 3.5); the dependence on line of sight; the first Fresnel
 * zone radius and the 60 % clearance rule; rain fade at 18 GHz; the three states of adaptive
 * modulation (full rate, stepped down, lost).
 *
 * <p><b>Game abstractions, labelled (NOTES.md, Phase 3 slice 11):</b>
 * <ul>
 *   <li><b>Obstruction</b> is the cellular material table along the line of sight times
 *       {@link #penetrationFactor} (3.0), the way each band scales it: one flat frequency factor, not
 *       a measured 18 GHz loss per material.</li>
 *   <li><b>Alignment is automatic.</b> Both dishes are taken as perfectly aimed at each other, so the
 *       full {@link #dishGainDbi} applies at both ends whatever the geometry. Real alignment is
 *       fiddly field work (a few tenths of a degree at 18 GHz), and a misaligned dish loses tens of dB.</li>
 *   <li><b>The Fresnel check is a simplified knife-edge approximation.</b> The 60 % clearance zone is
 *       sampled along four offset paths only (see {@link #evaluate}), and any hit on them costs a flat
 *       {@link #fresnelPenaltyDb} (6 dB, the knife-edge loss of an obstacle grazing the line of sight).
 *       A real obstacle inside the zone costs from about 0 dB (at 0.6 of the radius) to 6 dB (grazing),
 *       and more once it shadows the line, which here is the penetration term's job. Because the zone
 *       is sampled, not filled, a single block on the line of sight in mid-path, where the zone is
 *       widest, is charged its penetration loss but not the penalty, while the same block just beside
 *       the beam is charged the penalty: a leaf there costs 3 dB on the line and 6 dB beside it. Near a
 *       dish the zone is narrow and one block fills it, so a block on the line there costs both.</li>
 *   <li><b>Rain is approximate.</b> {@link #rainDbPerKm} and {@link #thunderDbPerKm} are figures in
 *       the spirit of ITU-R P.838 at 18 GHz (specific attenuation γ = k·R^α: about 2.5 dB/km is heavy
 *       rain near 25 mm/h, about 6 dB/km a thunderstorm downpour near 60 mm/h), applied over the whole
 *       hop when it rains at the hop's midpoint. A real rain cell covers part of a path (ITU-R P.530's
 *       path reduction factor), the rate varies, and the loss depends on polarisation; none of that is
 *       modelled. Snow adds nothing: dry snow attenuates far less than rain at this frequency.</li>
 * </ul>
 *
 * <p>Pure: no Minecraft imports ({@code PackagePurityTest}). The world reaches it through a
 * {@link WorldProbe} and a {@link Weather} the server reads at the midpoint.
 *
 * @param frequencyMhz         the link's carrier, {@code frequency_mhz} (18000).
 * @param txPowerDbm           transmitter power, {@code tx_power_dbm} (20).
 * @param dishGainDbi          gain of each dish, {@code dish_gain_dbi} (32); both ends count.
 * @param penetrationFactor    multiplier on the material table's per-block dB, {@code penetration_factor} (3.0).
 * @param upThresholdDbm       RSL at or above which the link is UP, {@code up_threshold_dbm} (−50).
 * @param degradedThresholdDbm RSL at or above which it is DEGRADED, {@code degraded_threshold_dbm} (−70).
 * @param fresnelPenaltyDb     loss added when the 60 % Fresnel zone is not clear, {@code fresnel_penalty_db} (6.0).
 * @param rainDbPerKm          rain fade per km of hop, {@code rain_db_per_km} (2.5).
 * @param thunderDbPerKm       rain fade per km in a thunderstorm, {@code thunder_db_per_km} (6.0).
 */
public record MicrowaveLink(
        double frequencyMhz,
        double txPowerDbm,
        double dishGainDbi,
        double penetrationFactor,
        double upThresholdDbm,
        double degradedThresholdDbm,
        double fresnelPenaltyDb,
        double rainDbPerKm,
        double thunderDbPerKm) {

    /** Speed of light in vacuum, m/s (exact by definition of the metre). */
    public static final double SPEED_OF_LIGHT_M_PER_S = 299_792_458.0;

    /** The fraction of the first Fresnel radius that must be clear: the usual 60 % planning rule. */
    public static final double FRESNEL_CLEARANCE = 0.6;

    /** The figures of §3C.2, identical to the shipped {@code microwave.json}. */
    public static final MicrowaveLink DEFAULT =
            new MicrowaveLink(18000.0, 20.0, 32.0, 3.0, -50.0, -70.0, 6.0, 2.5, 6.0);

    public MicrowaveLink {
        if (!(frequencyMhz > 0.0) || !Double.isFinite(frequencyMhz)) {
            throw new IllegalArgumentException("frequency_mhz must be positive and finite: " + frequencyMhz);
        }
        requireFinite("tx_power_dbm", txPowerDbm);
        requireFinite("dish_gain_dbi", dishGainDbi);
        requireNonNegative("penetration_factor", penetrationFactor);
        requireFinite("up_threshold_dbm", upThresholdDbm);
        requireFinite("degraded_threshold_dbm", degradedThresholdDbm);
        if (degradedThresholdDbm > upThresholdDbm) {
            throw new IllegalArgumentException("degraded_threshold_dbm (" + degradedThresholdDbm
                    + ") must not be above up_threshold_dbm (" + upThresholdDbm + ")");
        }
        requireNonNegative("fresnel_penalty_db", fresnelPenaltyDb);
        requireNonNegative("rain_db_per_km", rainDbPerKm);
        requireNonNegative("thunder_db_per_km", thunderDbPerKm);
    }

    /** A hop's state, as adaptive modulation would report it. */
    public enum LinkState {
        /** RSL ≥ the UP threshold: full rate, the high-order modulation holds. Carries a FULL backhaul. */
        UP,
        /** RSL ≥ the DEGRADED threshold: modulation stepped down, less capacity. Carries a LIMITED backhaul. */
        DEGRADED,
        /** Below both: the hop carries nothing. */
        DOWN
    }

    /**
     * The weather at a hop's midpoint, which the server reads from the level and the biome there.
     * Only rain attenuates; a thunderstorm is heavier rain.
     */
    public enum Weather {
        CLEAR,
        RAIN,
        THUNDER,
        /** Precipitation falls as snow at the midpoint: no rain fade. */
        SNOW;

        /**
         * The weather at a point, from the level's weather and what the biome makes of it there.
         *
         * @param levelRaining     the level is raining.
         * @param levelThundering  the level is thundering.
         * @param biomePrecipitation what the biome's precipitation is at the point: {@link #CLEAR} for
         *                         none (a desert), {@link #RAIN} for rain, {@link #SNOW} for snow
         *                         ({@link #THUNDER} is read as rain).
         * @return {@link #CLEAR} when the level is dry or the biome has no precipitation there,
         *         {@link #SNOW} when it snows there, otherwise {@link #THUNDER} in a thunderstorm and
         *         {@link #RAIN} in plain rain.
         */
        public static Weather at(boolean levelRaining, boolean levelThundering, Weather biomePrecipitation) {
            if (!(levelRaining || levelThundering) || biomePrecipitation == CLEAR) {
                return CLEAR;
            }
            if (biomePrecipitation == SNOW) {
                return SNOW;
            }
            return levelThundering ? THUNDER : RAIN;
        }
    }

    /**
     * One hop's evaluated budget.
     *
     * @param distanceMeters the hop's length between the two dish centres.
     * @param fsplDb         free-space path loss (the length floored at 1 m, as {@link RfMath} does).
     * @param obstructionDb  the material loss along the line of sight, penetration factor applied.
     * @param fresnelClear   true when none of the four offset paths at 60 % of the first Fresnel
     *                       radius hits a block with non-zero attenuation.
     * @param fresnelLossDb  {@link #fresnelPenaltyDb} when not clear, else 0.
     * @param rainLossDb     rain fade over the whole hop for the weather given (0 when clear or snowing).
     * @param rslDbm         received signal level; {@code -Infinity} when {@code outOfRange}.
     * @param marginDb       {@code rslDbm − upThresholdDbm}: the fade margin to full rate. Negative
     *                       once the link is below UP.
     * @param state          UP, DEGRADED or DOWN.
     * @param outOfRange     true when the line-of-sight march ran out of steps before reaching the far
     *                       dish: the hop is DOWN, {@code obstructionDb} is what was found before the
     *                       cap, and the Fresnel zone was not checked.
     */
    public record Budget(
            double distanceMeters,
            double fsplDb,
            double obstructionDb,
            boolean fresnelClear,
            double fresnelLossDb,
            double rainLossDb,
            double rslDbm,
            double marginDb,
            LinkState state,
            boolean outOfRange) {

        /**
         * One line for the dish's status and {@code /rancraft backhaul status} (Phase 3 slice 12): the
         * state, RSL, margin to UP, Fresnel state and rain loss (§3C.2), then the length and the
         * obstruction. Dot decimals whatever the locale.
         */
        public String describe() {
            if (outOfRange) {
                return String.format(java.util.Locale.ROOT, "DOWN, out of range (%.0f m)", distanceMeters);
            }
            String fresnel = fresnelClear
                    ? "Fresnel clear"
                    : String.format(java.util.Locale.ROOT, "Fresnel obstructed (+%.1f dB)", fresnelLossDb);
            return String.format(java.util.Locale.ROOT,
                    "%s, RSL %.1f dBm, margin %+.1f dB, %s, rain %.1f dB (%.0f m, obstruction %.1f dB)",
                    state, rslDbm, marginDb, fresnel, rainLossDb, distanceMeters, obstructionDb);
        }
    }

    /**
     * Free-space path loss over {@code distanceMeters}: {@code 20·log10(f_MHz) − 27.56 + 20·log10(d_m)},
     * with the distance floored at 1 m (as {@link RfMath#pathLossDb}) so that adjacent dishes give a
     * finite value. 117.55 dB at 18 GHz over 1000 m.
     */
    public double fsplDb(double distanceMeters) {
        return RfMath.fsplAt1mDb(frequencyMhz) + 20.0 * Math.log10(Math.max(distanceMeters, 1.0));
    }

    /** {@code λ = c / f}, in metres: 16.7 mm at 18 GHz. */
    public double wavelengthMeters() {
        return SPEED_OF_LIGHT_M_PER_S / (frequencyMhz * 1.0e6);
    }

    /**
     * Radius of the first Fresnel zone at fraction {@code t} along a hop of {@code distanceMeters}:
     * {@code r(t) = sqrt(λ · d · t(1 − t))}. 2.04 m at the midpoint of a 1000 m hop at 18 GHz; 0 at
     * either dish. {@code t} is clamped to {@code [0, 1]}.
     */
    public double fresnelRadiusMeters(double distanceMeters, double t) {
        double f = Math.min(Math.max(t, 0.0), 1.0);
        return Math.sqrt(wavelengthMeters() * Math.max(distanceMeters, 0.0) * f * (1.0 - f));
    }

    /**
     * Rain fade over the whole hop: {@code rain_db_per_km × d_km} in rain; in a thunderstorm the higher
     * of the two figures ({@code thunder_db_per_km} at the defaults), since a thunderstorm is at least
     * as wet as rain; nothing when clear or snowing. Approximate (class javadoc).
     */
    public double rainLossDb(Weather weather, double distanceMeters) {
        double km = Math.max(distanceMeters, 0.0) / 1000.0;
        return switch (weather) {
            case RAIN -> rainDbPerKm * km;
            case THUNDER -> Math.max(rainDbPerKm, thunderDbPerKm) * km;
            case CLEAR, SNOW -> 0.0;
        };
    }

    /** {@code RSL = Tx + 2·G_dish − FSPL − Obstruction − Fresnel − Rain}. */
    public double rslDbm(double fsplDb, double obstructionDb, double fresnelLossDb, double rainLossDb) {
        return txPowerDbm + 2.0 * dishGainDbi - fsplDb - obstructionDb - fresnelLossDb - rainLossDb;
    }

    /** UP at or above the UP threshold, DEGRADED at or above the DEGRADED threshold, else DOWN. NaN is DOWN. */
    public LinkState stateOf(double rslDbm) {
        if (rslDbm >= upThresholdDbm) {
            return LinkState.UP;
        }
        if (rslDbm >= degradedThresholdDbm) {
            return LinkState.DEGRADED;
        }
        return LinkState.DOWN;
    }

    /**
     * {@link #evaluate(WorldProbe, double, double, double, double, double, double, double, Weather, int)}
     * with the config's {@code metersPerBlock}, and the weather ignored when {@code enableRainFade} is off.
     */
    public Budget evaluate(WorldProbe probe,
                           double ax, double ay, double az,
                           double bx, double by, double bz,
                           RfConfig config, Weather weather, int maxSteps) {
        return evaluate(probe, ax, ay, az, bx, by, bz, config.metersPerBlock(),
                config.enableRainFade() ? weather : Weather.CLEAR, maxSteps);
    }

    /**
     * Evaluates the hop from dish A's centre to dish B's centre (block coordinates). Where a line runs
     * exactly through a voxel edge or corner the march's tie-breaking depends on its direction (as
     * {@link RayMarcher}'s own symmetry test allows), so a caller should evaluate each pair of dishes in
     * one fixed order, or the two ends of one hop could disagree.
     *
     * <ol>
     *   <li><b>Obstruction:</b> one {@link RayMarcher} march along the line of sight, the material dB
     *       times {@link #penetrationFactor}, with no early exit so the reported loss is exact. The two
     *       dishes' own voxels are skipped, as the cellular march skips the antenna and the receiver.</li>
     *   <li><b>Fresnel:</b> the midpoint is moved {@code 0.6 · r(0.5)} (in blocks: metres divided by
     *       {@code metersPerBlock}) up, down, left and right, perpendicular to the line: "left/right"
     *       horizontal, "up/down" in the vertical plane through the line (straight up for a level hop).
     *       Each offset path is two straight segments, dish A to the offset midpoint and on to dish B,
     *       marched with factor 1 and stopped at the first block with non-zero attenuation; the offset
     *       midpoint's own voxel, which both segments skip as an endpoint, is probed once. Any hit adds
     *       {@link #fresnelPenaltyDb}. The two segments trace a diamond inside the zone's ellipsoid
     *       (exact at the midpoint and the dishes, narrower between): part of the sampling abstraction
     *       in the class javadoc.</li>
     *   <li><b>Rain:</b> {@link #rainLossDb} for {@code weather} over the hop's length.</li>
     * </ol>
     *
     * Adding a block anywhere never raises the RSL: it can only add penetration loss, the Fresnel
     * penalty, or neither.
     *
     * @param metersPerBlock real metres per block ({@code RfConfig.metersPerBlock}); scales every
     *                       distance, as it does for the cellular engine.
     * @param weather        the weather at the midpoint ({@link Weather#at}).
     * @param maxSteps       voxel cap for each march. When the line-of-sight march hits it, the hop is
     *                       DOWN and {@link Budget#outOfRange()} is set.
     */
    public Budget evaluate(WorldProbe probe,
                           double ax, double ay, double az,
                           double bx, double by, double bz,
                           double metersPerBlock, Weather weather, int maxSteps) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double lengthBlocks = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double distanceMeters = lengthBlocks * metersPerBlock;

        double fspl = fsplDb(distanceMeters);
        double rain = rainLossDb(weather, distanceMeters);

        RayMarcher.MarchResult line = RayMarcher.march(probe, ax, ay, az, bx, by, bz,
                penetrationFactor, Double.POSITIVE_INFINITY, maxSteps);
        if (line.cappedOut()) {
            return new Budget(distanceMeters, fspl, line.obstructionDb(), true, 0.0, rain,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, LinkState.DOWN, true);
        }

        boolean fresnelClear = true;
        for (double[] offset : fresnelOffsetMidpoints(ax, ay, az, bx, by, bz, metersPerBlock)) {
            if (pathHits(probe, ax, ay, az, offset[0], offset[1], offset[2], bx, by, bz, maxSteps)) {
                fresnelClear = false;
                break;
            }
        }
        double fresnelLoss = fresnelClear ? 0.0 : fresnelPenaltyDb;

        double rsl = rslDbm(fspl, line.obstructionDb(), fresnelLoss, rain);
        return new Budget(distanceMeters, fspl, line.obstructionDb(), fresnelClear, fresnelLoss, rain,
                rsl, rsl - upThresholdDbm, stateOf(rsl), false);
    }

    /**
     * The same hop under different weather, without marching again: the rain term, the RSL, the margin
     * and the state are re-derived from {@code measured}'s distance, free-space, obstruction and Fresnel
     * terms, which no weather changes. So a weather change costs no block reads (§3C.2 recomputes on
     * one). {@code measured} must come from this link's {@link #evaluate}; an out-of-range hop stays
     * DOWN. The result equals a fresh {@link #evaluate} of the same hop and world with {@code weather}.
     */
    public Budget withWeather(Budget measured, Weather weather) {
        double rain = rainLossDb(weather, measured.distanceMeters());
        if (measured.outOfRange()) {
            return new Budget(measured.distanceMeters(), measured.fsplDb(), measured.obstructionDb(),
                    measured.fresnelClear(), measured.fresnelLossDb(), rain,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, LinkState.DOWN, true);
        }
        double rsl = rslDbm(measured.fsplDb(), measured.obstructionDb(), measured.fresnelLossDb(), rain);
        return new Budget(measured.distanceMeters(), measured.fsplDb(), measured.obstructionDb(),
                measured.fresnelClear(), measured.fresnelLossDb(), rain,
                rsl, rsl - upThresholdDbm, stateOf(rsl), false);
    }

    /** {@link #withWeather(Budget, Weather)}, with the weather ignored when {@code enableRainFade} is off. */
    public Budget withWeather(Budget measured, RfConfig config, Weather weather) {
        return withWeather(measured, config.enableRainFade() ? weather : Weather.CLEAR);
    }

    /**
     * The four points the Fresnel check's offset paths bend through, in the order up, down, left,
     * right: the hop's midpoint moved {@code 0.6 · r(0.5)} (in blocks) perpendicular to the line, as
     * {@link #evaluate} describes. Each offset path is dish A to one of these points, then on to dish B.
     * Empty for a zero-length hop, which has no zone to check.
     */
    public double[][] fresnelOffsetMidpoints(double ax, double ay, double az,
                                             double bx, double by, double bz,
                                             double metersPerBlock) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double lengthBlocks = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (lengthBlocks == 0.0) {
            return new double[0][];
        }
        double offsetBlocks = FRESNEL_CLEARANCE * fresnelRadiusMeters(lengthBlocks * metersPerBlock, 0.5)
                / metersPerBlock;
        double ux = dx / lengthBlocks;
        double uy = dy / lengthBlocks;
        double uz = dz / lengthBlocks;

        // Left/right: horizontal and perpendicular to the line. A vertical line has no horizontal
        // direction of its own, so x stands in.
        double horizontal = Math.hypot(ux, uz);
        double hx = horizontal < 1.0e-9 ? 1.0 : -uz / horizontal;
        double hz = horizontal < 1.0e-9 ? 0.0 : ux / horizontal;

        // Up/down: h × u (h has no y), perpendicular to both, turned to point up.
        double vx = -hz * uy;
        double vy = hz * ux - hx * uz;
        double vz = hx * uy;
        if (vy < 0.0) {
            vx = -vx;
            vy = -vy;
            vz = -vz;
        }

        double mx = ax + dx * 0.5;
        double my = ay + dy * 0.5;
        double mz = az + dz * 0.5;

        double[][] directions = {{vx, vy, vz}, {-vx, -vy, -vz}, {hx, 0.0, hz}, {-hx, 0.0, -hz}};
        double[][] points = new double[directions.length][];
        for (int i = 0; i < directions.length; i++) {
            double[] e = directions[i];
            points[i] = new double[] {mx + e[0] * offsetBlocks, my + e[1] * offsetBlocks, mz + e[2] * offsetBlocks};
        }
        return points;
    }

    /**
     * The hop's <b>dependency set</b>: the XZ bins ({@link BinTraversal#binsAlong}) along the line of
     * sight and along both segments of each Fresnel offset path, sorted by key, each once. Every voxel
     * {@link #evaluate} reads lies in one of them, so a block change outside these bins cannot change
     * the hop's obstruction or Fresnel term; only the weather and the two dish positions can change the
     * rest. (A chunk on the path loading or unloading changes what the server's probe reads there; the
     * server moves that bin's epoch then too, as it does for the cellular cache.) A superset when a march stops early (out of range, or an offset path's first hit), never a
     * subset ({@link BinTraversal} has the argument, corners included). The server's backhaul
     * recomputation watches these bins' region epochs (§3C.2: "a region epoch moves on any bin a link
     * crosses").
     *
     * @param binSize bin edge in blocks (the server passes {@code SiteRegistry.BIN_SIZE}).
     */
    public long[] dependencyBins(double ax, double ay, double az,
                                 double bx, double by, double bz,
                                 double metersPerBlock, int binSize) {
        double[][] offsets = fresnelOffsetMidpoints(ax, ay, az, bx, by, bz, metersPerBlock);
        long[][] perSegment = new long[1 + 2 * offsets.length][];
        perSegment[0] = BinTraversal.binsAlong(ax, az, bx, bz, binSize);
        for (int i = 0; i < offsets.length; i++) {
            double[] o = offsets[i];
            perSegment[1 + 2 * i] = BinTraversal.binsAlong(ax, az, o[0], o[2], binSize);
            perSegment[2 + 2 * i] = BinTraversal.binsAlong(o[0], o[2], bx, bz, binSize);
        }
        return BinTraversal.union(perSegment);
    }

    /**
     * A voxel cap for {@link #evaluate} that no march of this hop can run out of (Phase 3 slice 12):
     * the hop is then never {@link Budget#outOfRange() out of range}, however long, and its cost is
     * set by its length alone. A march between two points enters exactly {@code |Δx| + |Δy| + |Δz|}
     * voxels (the differences of their voxel coordinates, {@link RayMarcher}); a Fresnel segment
     * covers about half the line plus the offset, so the line's count plus six offsets and a margin
     * covers every march, the bend voxels' rounding included. The server bounds the length instead,
     * when the dishes are paired.
     */
    public int stepsToReach(double ax, double ay, double az,
                            double bx, double by, double bz,
                            double metersPerBlock) {
        long l1 = Math.abs((long) Math.floor(bx) - (long) Math.floor(ax))
                + Math.abs((long) Math.floor(by) - (long) Math.floor(ay))
                + Math.abs((long) Math.floor(bz) - (long) Math.floor(az));
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double lengthBlocks = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double offsetBlocks = metersPerBlock > 0.0
                ? FRESNEL_CLEARANCE * fresnelRadiusMeters(lengthBlocks * metersPerBlock, 0.5) / metersPerBlock
                : 0.0;
        long cap = l1 + 6L * (long) Math.ceil(offsetBlocks) + 8L;
        return (int) Math.min(Integer.MAX_VALUE, cap);
    }

    /** Dish A to the offset midpoint to dish B: true at the first voxel with non-zero attenuation. */
    private static boolean pathHits(WorldProbe probe,
                                    double ax, double ay, double az,
                                    double ox, double oy, double oz,
                                    double bx, double by, double bz,
                                    int maxSteps) {
        // maxObstructionDb 0 with factor 1: the march exits early at the first block that attenuates.
        if (RayMarcher.march(probe, ax, ay, az, ox, oy, oz, 1.0, 0.0, maxSteps).earlyExit()) {
            return true;
        }
        // Both segments skip the offset midpoint's voxel (one ends there, the other starts there).
        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);
        boolean dishVoxel = (x == (int) Math.floor(ax) && y == (int) Math.floor(ay) && z == (int) Math.floor(az))
                || (x == (int) Math.floor(bx) && y == (int) Math.floor(by) && z == (int) Math.floor(bz));
        if (!dishVoxel && probe.attenuationDbAt(x, y, z) > 0.0) {
            return true;
        }
        return RayMarcher.march(probe, ox, oy, oz, bx, by, bz, 1.0, 0.0, maxSteps).earlyExit();
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }

    private static void requireNonNegative(String name, double value) {
        if (!(value >= 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be zero or positive and finite: " + value);
        }
    }
}
