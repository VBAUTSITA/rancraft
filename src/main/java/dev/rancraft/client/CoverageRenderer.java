package dev.rancraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.rancraft.RanCraft;
import dev.rancraft.item.LensSettings;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.net.CoverageSurveyPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Paints the RF Lens best-server plot (Step 2b) onto the ground around the wearer.
 *
 * <p>One translucent tile per surveyed point, covering the {@code step x step} square the point
 * stands for, laid just above the ground the server stood the receiver on. The colour says which
 * cell serves there (a stable hue per cell), the opacity says how well (bolder is better), and
 * dark grey says nothing usable does. A tag in each cell's colour, over the middle of its area,
 * names its PCI, so the colours can be read without guessing.
 *
 * <p><b>Nothing here is computed.</b> The server surveyed every point, chose its best server and
 * classified its service; this is a restyling of {@link CoverageSurveyPayload}. The plot is
 * stateless best-server with no handover hysteresis, which is how planning tools draw it and why
 * it can differ from the live meter right at a boundary. {@link dev.rancraft.rf.CoverageSurvey}
 * documents that and the band-filter caveat.
 *
 * <p>A survey is drawn only while the lens's band filter matches the one it was made for, so
 * switching band never shows the old band's plot under the new band's name. Tile colours are
 * worked out once per survey ({@link CoverageTiles}); a frame only emits the quads.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID, value = Dist.CLIENT)
public final class CoverageRenderer {

    private CoverageRenderer() {
    }

    /**
     * Tiles sit this far above the ground surface: enough to win the depth test against the top
     * face of the block, too little to look like they float.
     */
    private static final double GROUND_LIFT = 0.03;

    /** Legend tags further away than this are not drawn. */
    private static final double LABEL_RANGE = 96.0;

    /** At most this many legend tags a frame, biggest areas first. */
    private static final int MAX_LABELS = 24;

    private static final int LABEL_ALPHA = 230;
    private static final int LABEL_GHOST_ALPHA = 110;

    /** Tiles for the survey last drawn; rebuilt when a new survey arrives. Render thread only. */
    private static CoverageTiles cached;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        ItemStack lens = RfLensItem.wornBy(player);
        if (lens == null) {
            return;
        }
        LensSettings settings = RfLensItem.settingsOf(lens);
        if (!settings.showCoverage()) {
            return;
        }
        CoverageSurveyPayload survey = ClientLensState.coverage();
        if (survey == null || !survey.bandFilter().equals(settings.bandFilter())) {
            return;
        }

        CoverageTiles tiles = tilesFor(survey);
        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        poseStack.pushPose();
        drawTiles(buffers, poseStack.last().pose(), eye, survey, tiles);
        if (!minecraft.options.hideGui) {
            WorldLabels.draw(poseStack, camera, legend(tiles, eye));
        }
        poseStack.popPose();
    }

    /**
     * Four vertices per drawn point into {@link RenderType#debugQuads()} (position and colour,
     * translucent, no culling). Coordinates are made camera-relative in double precision before
     * they become floats, so the painting stays steady far from the world origin.
     */
    private static void drawTiles(
            MultiBufferSource.BufferSource buffers, Matrix4f matrix, Vec3 eye,
            CoverageSurveyPayload survey, CoverageTiles tiles) {

        int size = survey.size();
        int step = survey.step();
        int[] surfaceY = survey.surfaceY();
        VertexConsumer quads = buffers.getBuffer(RenderType.debugQuads());
        for (int j = 0; j < size; j++) {
            float z0 = (float) (survey.blockZ(j) - eye.z);
            float z1 = z0 + step;
            for (int i = 0; i < size; i++) {
                int k = survey.index(i, j);
                int argb = tiles.argbAt(k);
                if (argb == LensStyle.NOT_DRAWN) {
                    continue;
                }
                int alpha = argb >>> 24;
                int red = (argb >> 16) & 0xFF;
                int green = (argb >> 8) & 0xFF;
                int blue = argb & 0xFF;
                float x0 = (float) (survey.blockX(i) - eye.x);
                float x1 = x0 + step;
                float y = (float) (surfaceY[k] + GROUND_LIFT - eye.y);

                quads.addVertex(matrix, x0, y, z0).setColor(red, green, blue, alpha);
                quads.addVertex(matrix, x0, y, z1).setColor(red, green, blue, alpha);
                quads.addVertex(matrix, x1, y, z1).setColor(red, green, blue, alpha);
                quads.addVertex(matrix, x1, y, z0).setColor(red, green, blue, alpha);
            }
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    /** The nearest few cells' PCI tags, biggest painted area first. */
    private static List<WorldLabels.Label> legend(CoverageTiles tiles, Vec3 eye) {
        double rangeSq = LABEL_RANGE * LABEL_RANGE;
        List<WorldLabels.Label> labels = new ArrayList<>();
        for (CoverageTiles.RegionLabel region : tiles.labels()) {
            if (labels.size() >= MAX_LABELS) {
                break;
            }
            double dx = region.x() - eye.x;
            double dy = region.y() - eye.y;
            double dz = region.z() - eye.z;
            if (dx * dx + dy * dy + dz * dz > rangeSq) {
                continue;
            }
            labels.add(new WorldLabels.Label(
                    region.x(), region.y(), region.z(), 0, region.text(), region.rgb(),
                    LABEL_ALPHA, LABEL_GHOST_ALPHA));
        }
        return labels;
    }

    private static CoverageTiles tilesFor(CoverageSurveyPayload survey) {
        CoverageTiles tiles = cached;
        if (tiles == null || tiles.survey() != survey) {
            tiles = CoverageTiles.of(survey);
            cached = tiles;
        }
        return tiles;
    }

    /** Dropped on disconnect and respawn along with the survey it was built from. */
    public static void clearCache() {
        cached = null;
    }
}
