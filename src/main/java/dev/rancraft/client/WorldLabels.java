package dev.rancraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Billboarded text in the world for the RF Lens: link labels at the antennas and legend tags over
 * the coverage painting.
 *
 * <p>Drawn the way vanilla draws a name tag, in two passes. The first is
 * {@link Font.DisplayMode#SEE_THROUGH} with a translucent background and faint text, so a label
 * behind a hill still shows where it is. The second is {@link Font.DisplayMode#NORMAL} at full
 * strength, so a label in plain view reads crisply. Every pass for every label is batched before
 * the buffers are flushed once.
 *
 * <p>Labels grow with distance, up to a cap, so an antenna across the valley is still legible
 * without one next to you filling the screen.
 */
final class WorldLabels {

    private WorldLabels() {
    }

    /** Block size of one font pixel at close range: the vanilla name-tag scale. */
    private static final float PIXEL = 0.025F;

    /** Up to this distance a label is drawn at name-tag size; beyond it, it grows with distance. */
    private static final double FULL_SIZE_DISTANCE = 12.0;

    /** Largest growth factor, reached at six times {@link #FULL_SIZE_DISTANCE}. */
    private static final double MAX_GROWTH = 6.0;

    /** Vertical gap between stacked lines, in font pixels. */
    private static final int LINE_GAP = 1;

    /**
     * One label.
     *
     * @param x          anchor, world coordinates.
     * @param stackIndex line within a stack at the same anchor, 0 at the bottom, counting upward.
     *                   Every line sits above the anchor.
     * @param rgb        text colour, 0xRRGGBB.
     * @param alpha      text opacity in plain view, 0..255.
     * @param ghostAlpha text opacity where the label is behind terrain.
     */
    record Label(double x, double y, double z, int stackIndex, String text, int rgb, int alpha, int ghostAlpha) {
    }

    static void draw(PoseStack poseStack, Camera camera, List<Label> labels) {
        if (labels.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        int background = (int) (minecraft.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        Vec3 eye = camera.getPosition();
        Quaternionf rotation = camera.rotation();

        for (Label label : labels) {
            drawOne(poseStack, font, buffers, eye, rotation, label,
                    Font.DisplayMode.SEE_THROUGH, LensStyle.argb(label.ghostAlpha(), label.rgb()), background);
        }
        for (Label label : labels) {
            drawOne(poseStack, font, buffers, eye, rotation, label,
                    Font.DisplayMode.NORMAL, LensStyle.argb(label.alpha(), label.rgb()), 0);
        }
        // Text spans several render types (glyph pages, backgrounds). Flushing everything is safe
        // at this stage: vanilla flushes the same buffer source right after it.
        buffers.endBatch();
    }

    private static void drawOne(
            PoseStack poseStack, Font font, MultiBufferSource buffers, Vec3 eye, Quaternionf rotation,
            Label label, Font.DisplayMode mode, int argb, int background) {

        double dx = label.x() - eye.x;
        double dy = label.y() - eye.y;
        double dz = label.z() - eye.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float scale = PIXEL * (float) Math.clamp(distance / FULL_SIZE_DISTANCE, 1.0, MAX_GROWTH);

        poseStack.pushPose();
        poseStack.translate(dx, dy, dz);
        poseStack.mulPose(rotation);
        poseStack.scale(scale, -scale, scale);
        Matrix4f pose = poseStack.last().pose();

        float textX = -font.width(label.text()) / 2.0F;
        // y grows downward in font space, so negative is up. Every line sits above the anchor.
        float textY = -(label.stackIndex() + 1) * (font.lineHeight + LINE_GAP);
        font.drawInBatch(label.text(), textX, textY, argb, false, pose, buffers, mode, background,
                LightTexture.FULL_BRIGHT);

        poseStack.popPose();
    }
}
