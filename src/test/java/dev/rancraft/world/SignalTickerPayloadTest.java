package dev.rancraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.rancraft.net.SignalSamplePayload;
import dev.rancraft.rf.Band;
import dev.rancraft.rf.BandTable;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.CellSample;
import dev.rancraft.rf.RfConfig;
import dev.rancraft.rf.ServiceLevel;
import dev.rancraft.rf.SignalSample;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 slice 4 regression gate, meter half: <b>the meter's payload is byte-identical</b> to the
 * one slice 0 (with its gate fixes) built for the same sample.
 *
 * <p>How the expected bytes were made: before the ticker was touched, a throwaway test called the
 * then-private {@code SignalTicker.toPayload} (commit {@code bd996d6}, band table from
 * {@code RfDataLoader.bands()}, which in a unit test is its built-in {@code BandTable.of(DEFAULT_900)})
 * by reflection on exactly these fixtures, encoded each result with the real
 * {@link SignalSamplePayload#STREAM_CODEC} and saved the hex. The throwaway test was then deleted.
 * These tests feed the same fixtures to the refactored {@link SignalTicker#toPayload} and compare
 * bytes. Re-checked independently after the refactor: {@code bd996d6}'s whole {@code SignalTicker},
 * run side by side in a throwaway test, produced exactly these three strings, and the same bytes as
 * the refactored code for 20,000 random samples (NOTES.md, Phase 3 slice 4). They cover the three paths: a served sample (serving cell held at rank 5, so the four-cell
 * cut must keep it; a PCI collision and a mod-3 warning, so the note must pick the collision), an
 * out-of-service sample, and a serving band the table does not know (judged as the fallback).
 */
class SignalTickerPayloadTest {

    private static final BandTable BANDS = BandTable.of(Band.DEFAULT_900);

    /** Cell 104 serves: PCI 10 collides with 102 (175 blocks away) and is mod-3 with 100 and 101. */
    private static final List<CellParams> CANDIDATES = List.of(
            new CellParams(100L, 10, 80, -20, "band_900", 20.0, 15.0, 90.0, 4.0, 65.0, 10.0, 7),
            new CellParams(101L, 200, 75, 30, "band_900", 20.0, 15.0, 270.0, 4.0, 65.0, 10.0, 7),
            new CellParams(102L, -150, 90, 60, "band_900", 20.0, 6.0, 0.0, 0.0, 360.0, 90.0, 10),
            new CellParams(103L, 40, 70, 400, "band_900", 23.0, 15.0, 180.0, 2.0, 65.0, 10.0, 11),
            new CellParams(104L, -60, 85, -90, "band_900", 20.0, 15.0, 45.0, 6.0, 65.0, 10.0, 10),
            new CellParams(105L, 300, 65, -300, "band_900", 20.0, 15.0, 315.0, 3.0, 65.0, 10.0, 13));

    /** Six cells, strongest first; hysteresis holds the receiver on the fifth (104). */
    private static SignalSample served() {
        List<CellSample> cells = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            CellParams p = CANDIDATES.get(i);
            cells.add(new CellSample(p.cellId(), p.x(), p.y(), p.z(), -71.25 - 1.5 * i, 123.5 + 17.0 * i,
                    98.75 + i, 2.5 * i, "band_900", p.pci(), 14.5 - i, -12.0 + 3.0 * i, -2.25 + 0.5 * i));
        }
        return new SignalSample(cells, 1_200_345L, 104L, 7.25, -88.5, -104.0, ServiceLevel.FAIR, 3);
    }

    private static SignalSample unknownBand() {
        CellSample cell = new CellSample(200L, 5, 66, 5, -60.0, 12.0, 70.0, 0.0,
                "band_unknown", 3, 6.0, 0.0, -1.0);
        return new SignalSample(List.of(cell), 1_200_385L, 200L, 30.0, Double.NEGATIVE_INFINITY, -104.0,
                ServiceLevel.EXCELLENT, 0);
    }

    private static String hex(SignalSamplePayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SignalSamplePayload.STREAM_CODEC.encode(buf, payload);
        return ByteBufUtil.hexDump(buf);
    }

    /**
     * The captured v3 bytes as v4 writes them (Phase 3 slice 12): the version byte (the first, a
     * one-byte VarInt) becomes 04 and the backhaul cap is appended, 04 (EXCELLENT, no cap: the ticker
     * sets a cap only when it sends). Every byte in between is the capture's, so the gate still holds.
     */
    private static String v4(String v3Bytes) {
        if (!v3Bytes.startsWith("03")) {
            throw new IllegalArgumentException("not a v3 capture");
        }
        return "04" + v3Bytes.substring(2) + "04";
    }

    // Captured from commit bd996d6 (slice 0 + gate fixes), before the slice 4 refactor.
    private static final String SERVED_BYTES = "030400000000000000640a50ecffffff0fc051d00000000000405ee000000000004058b0"
            + "000000000000000000000000000862616e645f39303007402d000000000000c028000000000000c00200000000000000"
            + "00000000000065c8014b1ec05230000000000040619000000000004058f0000000000040040000000000000862616e645f"
            + "39303007402b000000000000c022000000000000bffc0000000000000000000000000066eafeffff0f5a3cc05290000000"
            + "00004063b00000000000405930000000000040140000000000000862616e645f3930300a4029000000000000c018000000"
            + "000000bff40000000000000000000000000068c4ffffff0f55a6ffffff0fc0535000000000004067f000000000004059b0"
            + "000000000040240000000000000862616e645f3930300a40250000000000000000000000000000bfd0000000000000d9a1"
            + "490000000000000068401d000000000000c056200000000000c05a0000000000000203050862616e645f393030408c2000"
            + "000000003ff00000000000002950434920313020636f6c6c6964657320776974682073697465206174202d3135302c2039"
            + "302c203630c0934a00000000004051e7ae147ae148408eda000000000006";

    private static final String EMPTY_BYTES = "0300eda1498000000000000000fff0000000000000fff00000000000000000000000"
            + "0000000003000000000000000000003ff000000000000000c0934800000000004051e7ae147ae148408ee0000000000000";

    private static final String UNKNOWN_BAND_BYTES = "030100000000000000c8054205c04e0000000000004028000000000000405180"
            + "000000000000000000000000000c62616e645f756e6b6e6f776e0340180000000000000000000000000000bff0000000"
            + "00000081a24900000000000000c8403e000000000000fff0000000000000c05a0000000000000400000862616e645f3930"
            + "30408c2000000000003ff00000000000000040160000000000004050e7ae147ae148403100000000000001";

    @Test
    @DisplayName("a served sample (serving at rank 5, PCI collision + mod-3) encodes byte for byte as before the refactor")
    void servedSampleIsByteIdentical() {
        SignalSamplePayload payload = SignalTicker.toPayload(
                served(), CANDIDATES, 1_200_345L, RfConfig.DEFAULTS, BANDS, -1234.5, 71.62, 987.25);
        assertEquals("PCI 10 collides with site at -150, 90, 60", payload.servingConflictNote(),
                "fixture: the collision outranks the mod-3 warning");
        assertEquals(v4(SERVED_BYTES), hex(payload));
    }

    @Test
    @DisplayName("an out-of-service sample encodes byte for byte as before the refactor")
    void noServiceIsByteIdentical() {
        SignalSamplePayload payload = SignalTicker.toPayload(
                SignalSample.empty(1_200_365L, 3), CANDIDATES, 1_200_365L, RfConfig.DEFAULTS, BANDS,
                -1234.0, 71.62, 988.0);
        assertEquals(v4(EMPTY_BYTES), hex(payload));
    }

    @Test
    @DisplayName("a serving band missing from the table is sent as the fallback band, byte for byte as before")
    void unknownBandIsByteIdentical() {
        SignalSamplePayload payload = SignalTicker.toPayload(
                unknownBand(), CANDIDATES, 1_200_385L, RfConfig.DEFAULTS, BANDS, 5.5, 67.62, 17.0);
        assertEquals("band_900", payload.servingBandId());
        assertEquals(v4(UNKNOWN_BAND_BYTES), hex(payload));
    }
}
