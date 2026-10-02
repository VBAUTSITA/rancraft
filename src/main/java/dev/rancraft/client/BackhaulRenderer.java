package dev.rancraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.rancraft.RanCraft;
import dev.rancraft.item.LensSettings;
import dev.rancraft.item.RfLensItem;
import dev.rancraft.net.BackhaulLinksPayload;
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
 * Draws the microwave backhaul hops on the RF Lens (Phase 3 slice 12, §3C.2 "Visibility"): a line
 * between the two dishes of each hop near the wearer, in the colour of the state the server measured
 * (green UP, orange DEGRADED, red DOWN; {@link LensStyle#backhaulRgb}), labelled at its midpoint with
 * the server's RSL and margin. Drawn while the lobes layer is on: the hops are infrastructure, like the
 * antennas' declared patterns, and §3C.2 adds no lens setting for them (the settings codec is at its
 * six-field ceiling).
 *
 * <p><b>Every value drawn is server-supplied</b> ({@link BackhaulLinksPayload}). A hop's state depends
 * on every block along it, its Fresnel zone, the rain at its midpoint and the server's link figures;
 * the client knows only the two ends and the verdict, and draws a straight line between them.
 *
 * <p>Each line is drawn twice, as the link rays are ({@link LinkRenderer}): faintly through terrain,
 * then at full strength with the depth test.
 */
@EventBusSubscriber(modid = RanCraft.MOD_ID, value = Dist.CLIENT)
public final class BackhaulRenderer {

    private BackhaulRenderer() {
    }

    private static final int LINE_ALPHA = 230;
    private static final int GHOST_ALPHA = 70;
    private static final int LABEL_ALPHA = 255;
    private static final int LABEL_GHOST_ALPHA = 110;
    /** Labels sit this far above the hop's midpoint. */
    private static final double LABEL_LIFT = 0.6;

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
        if (!settings.showLobes()) {
            return;
        }
        List<BackhaulLinksPayload.Link> links = ClientLensState.backhaul().links();
        if (links.isEmpty()) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        poseStack.pushPose();
        Matrix4f matrix = poseStack.last().pose();

        VertexConsumer ghost = buffers.getBuffer(LinkRenderer.SeeThroughLines.TYPE);
        for (BackhaulLinksPayload.Link link : links) {
            drawLine(ghost, matrix, eye, link, GHOST_ALPHA);
        }
        buffers.endBatch(LinkRenderer.SeeThroughLines.TYPE);

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (BackhaulLinksPayload.Link link : links) {
            drawLine(lines, matrix, eye, link, LINE_ALPHA);
        }
        buffers.endBatch(RenderType.lines());

        if (!minecraft.options.hideGui) {
            List<WorldLabels.Label> labels = new ArrayList<>(links.size());
            for (BackhaulLinksPayload.Link link : links) {
                labels.add(new WorldLabels.Label(
                        (link.ax() + link.bx()) * 0.5 + 0.5,
                        (link.ay() + link.by()) * 0.5 + 0.5 + LABEL_LIFT,
                        (link.az() + link.bz()) * 0.5 + 0.5,
                        0,
                        LensStyle.backhaulLabel(link.state(), link.rslDbm(), link.marginDb()),
                        LensStyle.backhaulRgb(link.state()),
                        LABEL_ALPHA, LABEL_GHOST_ALPHA));
            }
            WorldLabels.draw(poseStack, camera, labels);
        }
        poseStack.popPose();
    }

    /**
     * One hop, dish centre to dish centre, in its state's colour. Coordinates are made camera-relative
     * in double precision before they become floats, so lines stay steady far from the world origin.
     */
    private static void drawLine(VertexConsumer consumer, Matrix4f matrix, Vec3 eye,
                                 BackhaulLinksPayload.Link link, int alpha) {
        double ax = link.ax() + 0.5;
        double ay = link.ay() + 0.5;
        double az = link.az() + 0.5;
        double dx = link.bx() + 0.5 - ax;
        double dy = link.by() + 0.5 - ay;
        double dz = link.bz() + 0.5 - az;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-4) {
            return;
        }
        float nx = (float) (dx / length);
        float ny = (float) (dy / length);
        float nz = (float) (dz / length);
        int rgb = LensStyle.backhaulRgb(link.state());
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        consumer.addVertex(matrix, (float) (ax - eye.x), (float) (ay - eye.y), (float) (az - eye.z))
                .setColor(red, green, blue, alpha)
                .setNormal(nx, ny, nz);
        consumer.addVertex(matrix, (float) (ax + dx - eye.x), (float) (ay + dy - eye.y), (float) (az + dz - eye.z))
                .setColor(red, green, blue, alpha)
                .setNormal(nx, ny, nz);
    }
}
