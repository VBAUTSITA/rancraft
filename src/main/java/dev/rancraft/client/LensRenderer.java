package dev.rancraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.rancraft.RanCraft;
import dev.rancraft.block.AntennaBlockEntity;
import dev.rancraft.item.LensSettings;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.rf.AntennaPattern;
import dev.rancraft.rf.CellParams;
import dev.rancraft.rf.ParabolicPattern;
import dev.rancraft.rf.PatternMesh;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Draws antenna radiation patterns in the world for a player wearing the RF Lens.
 *
 * <p><b>This renderer measures nothing.</b> It reads each antenna's declared configuration -- which
 * the block entity already syncs to the client as public data -- and runs the same
 * {@link AntennaPattern} object the server evaluates with. RSRP, SINR and serving-cell choice are
 * never computed here; they arrive from the server as values, exactly as before. See VISION.md.
 *
 * <p>Meshes are cached per cell configuration, so standing still costs nothing and only an actual
 * edit rebuilds geometry.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID, value = Dist.CLIENT)
public final class LensRenderer {

    private LensRenderer() {
    }

    /** How far away an antenna can be and still get a lobe drawn, in blocks. */
    private static final double RENDER_DISTANCE = 96.0;

    /** World-space radius of a full-gain lobe, in blocks. Shape, never reach -- see VISION.md. */
    private static final double LOBE_RADIUS = 8.0;

    /** Cap on lobes per frame, so a dense cluster cannot tank the framerate. */
    private static final int MAX_LOBES = 24;

    /** Alpha for each shell, outermost first. Fainter outside, brighter at the core. */
    private static final int[] SHELL_ALPHA = {70, 120, 200};

    /** Cached geometry, keyed by the exact configuration that produced it. */
    private static final Map<MeshKey, List<PatternMesh.Shell>> MESH_CACHE = new HashMap<>();

    /**
     * Everything the mesh depends on. Position is deliberately absent: two antennas with identical
     * settings share one mesh and are simply drawn at different offsets.
     */
    private record MeshKey(
            double azimuthDeg, double tiltDeg,
            double hBeamwidthDeg, double vBeamwidthDeg, double gainDbi) {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }

        ItemStack lens = RfLensItem.wornBy(player);
        if (lens == null) {
            return;
        }
        LensSettings settings = RfLensItem.settingsOf(lens);
        if (!settings.showLobes()) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        // ClientLevel keeps no flat block-entity list, so walk the loaded chunks in range instead.
        int drawn = 0;
        int chunkRadius = Mth.ceil(RENDER_DISTANCE / 16.0);
        int centreX = SectionPos.blockToSectionCoord(Mth.floor(camera.x));
        int centreZ = SectionPos.blockToSectionCoord(Mth.floor(camera.z));
        double rangeSq = RENDER_DISTANCE * RENDER_DISTANCE;

        outer:
        for (int cx = centreX - chunkRadius; cx <= centreX + chunkRadius; cx++) {
            for (int cz = centreZ - chunkRadius; cz <= centreZ + chunkRadius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }

                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (drawn >= MAX_LOBES) {
                        break outer;
                    }
                    if (!(blockEntity instanceof AntennaBlockEntity antenna)) {
                        continue;
                    }
                    if (!settings.showsBand(antenna.bandId())) {
                        continue;
                    }

                    BlockPos point = antenna.radiatingPoint();
                    double dx = (point.getX() + 0.5) - camera.x;
                    double dy = (point.getY() + 0.5) - camera.y;
                    double dz = (point.getZ() + 0.5) - camera.z;
                    if (dx * dx + dy * dy + dz * dz > rangeSq) {
                        continue;
                    }

                    drawLobe(poseStack, consumer, antenna, point);
                    drawn++;
                }
            }
        }

        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static void drawLobe(
            PoseStack poseStack, VertexConsumer consumer, AntennaBlockEntity antenna, BlockPos point) {

        CellParams cell = antenna.toCellParams();
        List<PatternMesh.Shell> shells = meshFor(cell);
        int colour = BandColours.of(cell.bandId());

        Matrix4f matrix = poseStack.last().pose();
        float ox = point.getX() + 0.5f;
        float oy = point.getY() + 0.5f;
        float oz = point.getZ() + 0.5f;

        for (int i = 0; i < shells.size(); i++) {
            int alpha = SHELL_ALPHA[Math.min(i, SHELL_ALPHA.length - 1)];
            int red = (colour >> 16) & 0xFF;
            int green = (colour >> 8) & 0xFF;
            int blue = colour & 0xFF;

            List<PatternMesh.Vertex> lines = shells.get(i).lines();
            // Vertices arrive as point pairs, which is exactly what a LINES draw wants.
            for (int v = 0; v + 1 < lines.size(); v += 2) {
                PatternMesh.Vertex a = lines.get(v);
                PatternMesh.Vertex b = lines.get(v + 1);
                segment(consumer, matrix, ox, oy, oz, a, b, red, green, blue, alpha);
            }
        }
    }

    private static void segment(
            VertexConsumer consumer, Matrix4f matrix,
            float ox, float oy, float oz,
            PatternMesh.Vertex a, PatternMesh.Vertex b,
            int red, int green, int blue, int alpha) {

        float ax = ox + (float) (a.x() * LOBE_RADIUS);
        float ay = oy + (float) (a.y() * LOBE_RADIUS);
        float az = oz + (float) (a.z() * LOBE_RADIUS);
        float bx = ox + (float) (b.x() * LOBE_RADIUS);
        float by = oy + (float) (b.y() * LOBE_RADIUS);
        float bz = oz + (float) (b.z() * LOBE_RADIUS);

        // RenderType.lines() wants a normal per vertex; the segment direction is the honest one.
        float nx = bx - ax;
        float ny = by - ay;
        float nz = bz - az;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-5f) {
            return;
        }
        nx /= length;
        ny /= length;
        nz /= length;

        consumer.addVertex(matrix, ax, ay, az).setColor(red, green, blue, alpha).setNormal(nx, ny, nz);
        consumer.addVertex(matrix, bx, by, bz).setColor(red, green, blue, alpha).setNormal(nx, ny, nz);
    }

    /** Builds geometry on demand and reuses it for every identically configured antenna. */
    private static List<PatternMesh.Shell> meshFor(CellParams cell) {
        MeshKey key = new MeshKey(
                cell.azimuthDeg(), cell.tiltDeg(),
                cell.hBeamwidthDeg(), cell.vBeamwidthDeg(), cell.gainDbi());

        return MESH_CACHE.computeIfAbsent(key, ignored -> {
            AntennaPattern pattern = AntennaPattern.forCell(cell,
                    ParabolicPattern.DEFAULT_FRONT_TO_BACK_DB,
                    ParabolicPattern.DEFAULT_SIDELOBE_FLOOR_DB);
            return PatternMesh.build(cell, pattern);
        });
    }

    /** Dropped on disconnect so a second world does not inherit the first one's geometry. */
    public static void clearCache() {
        MESH_CACHE.clear();
    }
}
