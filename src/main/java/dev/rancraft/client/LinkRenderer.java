package dev.rancraft.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.rancraft.RanCraft;
import dev.rancraft.item.LensSettings;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.net.LensLinksPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the RF Lens link rays (Step 2a): a line from every cell the server heard at the wearer's
 * head to the wearer, coloured along its length by how much the terrain has taken so far.
 *
 * <p><b>Every value drawn is server-supplied.</b> Each {@link LensLinksPayload.Link} carries the
 * antenna position, the RSRP the server measured, whether it serves, and {@code (t, cumulativeDb)}
 * breakpoints from the server's own ray march. This class draws a straight line between two points
 * it already knows and paints each stretch with the loss the server reported for it (see
 * {@link LensStyle#lossRgb}: green when clear, red at 40 dB lost). It never marches a ray, sums an
 * obstruction or picks a serving cell.
 *
 * <p><b>Between samples the breakpoints are re-projected.</b> The server traces from the antenna to
 * where the wearer's eye was at its last sample, about once a second; this draws to where the eye
 * is now, every frame, and places each breakpoint at the same fraction {@code t} of the current
 * line. Walking a few blocks therefore slides the red stretch a little along the ray until the
 * next sample corrects it. That is purely visual -- the numbers are last second's, and the label
 * says so by reading exactly what the meter would.
 *
 * <p><b>In first person the ray ends just in front of the eye, not in it.</b> A line that ends at
 * the camera projects to a single point, so all the rays would vanish. Drawing to a point a little
 * ahead of and below the view makes them converge just under the crosshair instead. In third
 * person the ray ends at the eye itself.
 *
 * <p>Each ray is drawn twice, like a name tag: faintly with no depth test, so the stretch inside a
 * hill still shows reddening where the hill eats the signal, then at full strength with the depth
 * test, so the unobstructed stretches read clearly.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID, value = Dist.CLIENT)
public final class LinkRenderer {

    private LinkRenderer() {
    }

    /** The serving link is opaque; the rest are fainter, so the one that matters stands out. */
    private static final int SERVING_ALPHA = 255;
    private static final int OTHER_ALPHA = 120;

    /** Opacity of the through-terrain pass. Enough to follow, not enough to clutter. */
    private static final int SERVING_GHOST_ALPHA = 80;
    private static final int OTHER_GHOST_ALPHA = 40;

    /** Label text opacity, in plain view and behind terrain. */
    private static final int SERVING_LABEL_ALPHA = 255;
    private static final int OTHER_LABEL_ALPHA = 200;
    private static final int SERVING_LABEL_GHOST_ALPHA = 140;
    private static final int OTHER_LABEL_GHOST_ALPHA = 90;

    /** First person: how far ahead of the eye, and how far below the view centre, rays end. */
    private static final double FIRST_PERSON_AHEAD = 0.6;
    private static final double FIRST_PERSON_BELOW = 0.2;

    /** Antennas within this horizontal distance share one label stack (a mast column, a site). */
    private static final double LABEL_GROUP_RADIUS = 2.5;

    /** Label stacks start this far above the highest antenna in the group. */
    private static final double LABEL_LIFT = 0.75;

    /**
     * {@link RenderType#lines()} with the depth test off and depth writes off, for the faint
     * through-terrain pass. Everything else matches vanilla lines, including the output target, so
     * both passes land in the same place under Fabulous graphics.
     *
     * <p>In a holder so it is built on the first frame that draws a ray, not when this subscriber
     * class is loaded during mod construction.
     */
    private static final class SeeThroughLines {

        private SeeThroughLines() {
        }

        static final RenderType TYPE = RenderType.create(
                "rancraft_lens_lines_see_through",
                DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES,
                1536,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                        .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(false));
    }

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
        if (!settings.showLinks() || ClientLensState.linksStale()) {
            return;
        }

        List<LensLinksPayload.Link> shown = new ArrayList<>();
        for (LensLinksPayload.Link link : ClientLensState.links().links()) {
            if (settings.showsBand(link.bandId())) {
                shown.add(link);
            }
        }
        if (shown.isEmpty()) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 receiver = receiverEnd(camera, player, partialTick);

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        poseStack.pushPose();
        Matrix4f matrix = poseStack.last().pose();

        // Lines and the see-through lines share the buffer source's one shared buffer, so each
        // pass is finished before the next asks for a buffer.
        VertexConsumer ghost = buffers.getBuffer(SeeThroughLines.TYPE);
        for (LensLinksPayload.Link link : shown) {
            drawLink(ghost, matrix, eye, receiver, link,
                    link.serving() ? SERVING_GHOST_ALPHA : OTHER_GHOST_ALPHA);
        }
        buffers.endBatch(SeeThroughLines.TYPE);

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (LensLinksPayload.Link link : shown) {
            drawLink(lines, matrix, eye, receiver, link, link.serving() ? SERVING_ALPHA : OTHER_ALPHA);
        }
        buffers.endBatch(RenderType.lines());

        if (!minecraft.options.hideGui) {
            WorldLabels.draw(poseStack, camera, labels(shown));
        }
        poseStack.popPose();
    }

    /**
     * Where the rays end: the wearer's eye in third person, or just ahead of and below it in first
     * person (see the class comment for why).
     */
    private static Vec3 receiverEnd(Camera camera, LocalPlayer player, float partialTick) {
        if (camera.isDetached() || camera.getEntity() != player) {
            return player.getEyePosition(partialTick);
        }
        Vec3 eye = camera.getPosition();
        Vector3f look = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        return new Vec3(
                eye.x + look.x() * FIRST_PERSON_AHEAD - up.x() * FIRST_PERSON_BELOW,
                eye.y + look.y() * FIRST_PERSON_AHEAD - up.y() * FIRST_PERSON_BELOW,
                eye.z + look.z() * FIRST_PERSON_AHEAD - up.z() * FIRST_PERSON_BELOW);
    }

    /**
     * One ray, split at the server's breakpoints, each stretch in the colour of the loss the server
     * reported at its start. Coordinates are made camera-relative in double precision before they
     * become floats, so rays stay steady far from the world origin.
     */
    private static void drawLink(
            VertexConsumer consumer, Matrix4f matrix, Vec3 eye, Vec3 receiver,
            LensLinksPayload.Link link, int alpha) {

        double ax = link.centerX();
        double ay = link.centerY();
        double az = link.centerZ();
        double lx = receiver.x - ax;
        double ly = receiver.y - ay;
        double lz = receiver.z - az;
        double length = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (length < 1.0e-4) {
            return;
        }
        // RenderType.lines() wants a normal per vertex; the line direction is the honest one.
        float nx = (float) (lx / length);
        float ny = (float) (ly / length);
        float nz = (float) (lz / length);

        for (LensStyle.Piece piece : LensStyle.pieces(link.breakpointT(), link.breakpointDb())) {
            int rgb = LensStyle.lossRgb(piece.lossDb());
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            consumer.addVertex(matrix,
                            (float) (ax + lx * piece.t0() - eye.x),
                            (float) (ay + ly * piece.t0() - eye.y),
                            (float) (az + lz * piece.t0() - eye.z))
                    .setColor(red, green, blue, alpha)
                    .setNormal(nx, ny, nz);
            consumer.addVertex(matrix,
                            (float) (ax + lx * piece.t1() - eye.x),
                            (float) (ay + ly * piece.t1() - eye.y),
                            (float) (az + lz * piece.t1() - eye.z))
                    .setColor(red, green, blue, alpha)
                    .setNormal(nx, ny, nz);
        }
    }

    /**
     * {@code PCI · band · RSRP} at each antenna, in the band's lobe colour. Co-sited antennas share
     * one stack, highest antenna on top, so a column of stacked masts reads as a list.
     */
    private static List<WorldLabels.Label> labels(List<LensLinksPayload.Link> links) {
        int n = links.size();
        double[] x = new double[n];
        double[] y = new double[n];
        double[] z = new double[n];
        for (int k = 0; k < n; k++) {
            x[k] = links.get(k).centerX();
            y[k] = links.get(k).centerY();
            z[k] = links.get(k).centerZ();
        }
        LensStyle.Anchor[] anchors = LensStyle.stack(x, y, z, LABEL_GROUP_RADIUS, LABEL_LIFT);

        List<WorldLabels.Label> labels = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            LensLinksPayload.Link link = links.get(k);
            LensStyle.Anchor anchor = anchors[k];
            boolean serving = link.serving();
            labels.add(new WorldLabels.Label(
                    anchor.x(), anchor.y(), anchor.z(), anchor.stackIndex(),
                    LensStyle.linkLabel(link.pci(), link.bandId(), link.rsrpDbm(), serving),
                    BandColours.of(link.bandId()),
                    serving ? SERVING_LABEL_ALPHA : OTHER_LABEL_ALPHA,
                    serving ? SERVING_LABEL_GHOST_ALPHA : OTHER_LABEL_GHOST_ALPHA));
        }
        return labels;
    }
}
