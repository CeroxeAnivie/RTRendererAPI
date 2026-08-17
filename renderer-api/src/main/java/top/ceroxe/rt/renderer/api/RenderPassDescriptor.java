package top.ceroxe.rt.renderer.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable attachment set and exact render area for one graphics pass.
 *
 * <p>Every attachment must describe the same extent, layer count, and sample count. Depth and
 * stencil remain separate declarations because they have independent load/store operations even
 * when both aspects reside in one physical texture.</p>
 */
public final class RenderPassDescriptor {
    private final int width;
    private final int height;
    private final int layerCount;
    private final int sampleCount;
    private final List<RenderAttachment> colorAttachments;
    private final List<Optional<TextureView>> colorResolveAttachments;
    private final Optional<RenderAttachment> depthAttachment;
    private final Optional<RenderAttachment> stencilAttachment;

    /**
     * Creates and validates an exact pass attachment set.
     *
     * @param width positive render-area width
     * @param height positive render-area height
     * @param layerCount positive rendered layer count
     * @param colorAttachments ordered color attachment locations
     * @param depthAttachment optional depth aspect
     * @param stencilAttachment optional stencil aspect
     */
    public RenderPassDescriptor(
            int width,
            int height,
            int layerCount,
            List<RenderAttachment> colorAttachments,
            Optional<RenderAttachment> depthAttachment,
            Optional<RenderAttachment> stencilAttachment
    ) {
        this(width, height, layerCount, colorAttachments,
                emptyResolves(Objects.requireNonNull(colorAttachments, "colorAttachments").size()),
                depthAttachment, stencilAttachment);
    }

    /**
     * Creates a pass with optional single-sample resolve targets aligned to color attachment locations.
     * A resolve is explicit rather than inferred from sample count, so a backend can never silently
     * discard multisample results or overwrite an unrelated texture.
     */
    public RenderPassDescriptor(
            int width,
            int height,
            int layerCount,
            List<RenderAttachment> colorAttachments,
            List<Optional<TextureView>> colorResolveAttachments,
            Optional<RenderAttachment> depthAttachment,
            Optional<RenderAttachment> stencilAttachment
    ) {
        if (width <= 0 || height <= 0 || layerCount <= 0) {
            throw new IllegalArgumentException("render pass width, height, and layer count must be positive");
        }
        this.width = width;
        this.height = height;
        this.layerCount = layerCount;
        Objects.requireNonNull(colorAttachments, "colorAttachments");
        ArrayList<RenderAttachment> copiedColors = new ArrayList<>(colorAttachments.size());
        for (RenderAttachment attachment : colorAttachments) {
            RenderAttachment checked = Objects.requireNonNull(attachment, "color attachment");
            requireAspect(checked, TextureAspect.COLOR, "color");
            copiedColors.add(checked);
        }
        this.colorAttachments = List.copyOf(copiedColors);
        this.colorResolveAttachments = validateResolves(colorResolveAttachments, this.colorAttachments,
                width, height, layerCount);
        this.depthAttachment = requireOptionalAspect(depthAttachment, TextureAspect.DEPTH, "depth");
        this.stencilAttachment = requireOptionalAspect(stencilAttachment, TextureAspect.STENCIL, "stencil");
        if (this.colorAttachments.isEmpty() && this.depthAttachment.isEmpty() && this.stencilAttachment.isEmpty()) {
            throw new IllegalArgumentException("render pass must declare at least one attachment");
        }

        ArrayList<RenderAttachment> all = new ArrayList<>(this.colorAttachments);
        this.depthAttachment.ifPresent(all::add);
        this.stencilAttachment.ifPresent(all::add);
        this.sampleCount = all.get(0).view().texture().sampleCount();
        for (int index = 0; index < all.size(); index++) {
            RenderAttachment attachment = all.get(index);
            requireCompatibleExtent(attachment);
            if (attachment.view().texture().sampleCount() != sampleCount) {
                throw new IllegalArgumentException("all render pass attachments must have the same sample count");
            }
            for (int preceding = 0; preceding < index; preceding++) {
                if (overlaps(all.get(preceding).view(), attachment.view())) {
                    throw new IllegalArgumentException("render attachments overlap the same texture aspect subresources");
                }
            }
        }
        for (Optional<TextureView> optionalResolve : this.colorResolveAttachments) {
            if (optionalResolve.isEmpty()) continue;
            TextureView resolve = optionalResolve.orElseThrow();
            for (RenderAttachment attachment : all) {
                if (overlaps(resolve, attachment.view())) {
                    throw new IllegalArgumentException("render resolve target overlaps another pass attachment");
                }
            }
        }
        requireDepthStencilAliasConsistency();
    }

    /** Convenience factory for a color-only, single-layer pass. */
    public static RenderPassDescriptor color(int width, int height, List<RenderAttachment> colorAttachments) {
        return new RenderPassDescriptor(
                width, height, 1, colorAttachments, Optional.empty(), Optional.empty()
        );
    }

    public int width() { return width; }

    public int height() { return height; }

    public int layerCount() { return layerCount; }

    public int sampleCount() { return sampleCount; }

    public List<RenderAttachment> colorAttachments() { return colorAttachments; }

    /** @return immutable color resolve targets aligned to color attachment locations */
    public List<Optional<TextureView>> colorResolveAttachments() { return colorResolveAttachments; }

    public Optional<RenderAttachment> depthAttachment() { return depthAttachment; }

    public Optional<RenderAttachment> stencilAttachment() { return stencilAttachment; }

    private static Optional<RenderAttachment> requireOptionalAspect(
            Optional<RenderAttachment> attachment,
            TextureAspect aspect,
            String name
    ) {
        Optional<RenderAttachment> checked = Objects.requireNonNull(attachment, name + "Attachment");
        checked.ifPresent(value -> requireAspect(value, aspect, name));
        return checked;
    }

    private static List<Optional<TextureView>> emptyResolves(int count) {
        ArrayList<Optional<TextureView>> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) result.add(Optional.empty());
        return List.copyOf(result);
    }

    private static List<Optional<TextureView>> validateResolves(
            List<Optional<TextureView>> candidates,
            List<RenderAttachment> colors,
            int width,
            int height,
            int layerCount
    ) {
        Objects.requireNonNull(candidates, "colorResolveAttachments");
        if (candidates.size() != colors.size()) {
            throw new IllegalArgumentException("color resolve target count must match color attachment count");
        }
        ArrayList<Optional<TextureView>> checked = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Optional<TextureView> optional = Objects.requireNonNull(candidates.get(index), "color resolve target");
            RenderAttachment source = colors.get(index);
            optional.ifPresent(resolve -> {
                TextureView view = Objects.requireNonNull(resolve, "color resolve view");
                if (source.view().texture().sampleCount() == 1 || view.texture().sampleCount() != 1) {
                    throw new IllegalArgumentException("color resolve requires multisampled source and single-sample target");
                }
                if (view.range().aspect() != TextureAspect.COLOR
                        || view.texture().format() != source.view().texture().format()
                        || !view.texture().usage().contains(TextureUsage.COLOR_ATTACHMENT)
                        || view.dimension() != TextureViewDimension.TEXTURE_2D
                                && view.dimension() != TextureViewDimension.TEXTURE_2D_ARRAY) {
                    throw new IllegalArgumentException("color resolve target is not a compatible color attachment view");
                }
                TextureSubresourceRange range = view.range();
                if (range.mipLevelCount() != 1 || range.arrayLayerCount() != layerCount
                        || mipExtent(view.texture().width(), range.baseMipLevel()) != width
                        || mipExtent(view.texture().height(), range.baseMipLevel()) != height) {
                    throw new IllegalArgumentException("color resolve target extent does not match the render pass");
                }
            });
            checked.add(optional);
        }
        return List.copyOf(checked);
    }

    private static void requireAspect(RenderAttachment attachment, TextureAspect aspect, String name) {
        if (attachment.view().range().aspect() != aspect) {
            throw new IllegalArgumentException(name + " attachment has the wrong texture aspect");
        }
    }

    private void requireCompatibleExtent(RenderAttachment attachment) {
        TextureView view = attachment.view();
        if (view.dimension() != TextureViewDimension.TEXTURE_2D
                && view.dimension() != TextureViewDimension.TEXTURE_2D_ARRAY
                && view.dimension() != TextureViewDimension.TEXTURE_2D_MULTISAMPLED
                && view.dimension() != TextureViewDimension.TEXTURE_2D_MULTISAMPLED_ARRAY) {
            throw new IllegalArgumentException("render attachments must use two-dimensional views");
        }
        TextureSubresourceRange range = view.range();
        TextureResource texture = view.texture();
        int mipWidth = mipExtent(texture.width(), range.baseMipLevel());
        int mipHeight = mipExtent(texture.height(), range.baseMipLevel());
        if (mipWidth != width || mipHeight != height || range.arrayLayerCount() != layerCount) {
            throw new IllegalArgumentException("render attachment extent or layer count does not match the pass");
        }
    }

    private void requireDepthStencilAliasConsistency() {
        if (depthAttachment.isEmpty() || stencilAttachment.isEmpty()) return;
        TextureView depth = depthAttachment.orElseThrow().view();
        TextureView stencil = stencilAttachment.orElseThrow().view();
        boolean sameIdentity = depth.texture().id().equals(stencil.texture().id())
                && depth.texture().version().equals(stencil.texture().version());
        if (!sameIdentity
                || depth.texture().format() != stencil.texture().format()
                || depth.range().baseMipLevel() != stencil.range().baseMipLevel()
                || depth.range().baseArrayLayer() != stencil.range().baseArrayLayer()
                || depth.range().arrayLayerCount() != stencil.range().arrayLayerCount()) {
            throw new IllegalArgumentException("depth and stencil attachments must be aspects of the same subresources");
        }
    }

    private static int mipExtent(int baseExtent, int mipLevel) {
        return Math.max(1, baseExtent >> Math.min(mipLevel, Integer.SIZE - 1));
    }

    private static boolean overlaps(TextureView first, TextureView second) {
        TextureSubresourceRange firstRange = first.range();
        TextureSubresourceRange secondRange = second.range();
        return first.texture().id().equals(second.texture().id())
                && first.texture().version().equals(second.texture().version())
                && firstRange.aspect() == secondRange.aspect()
                && firstRange.baseMipLevel() < secondRange.mipEndExclusive()
                && secondRange.baseMipLevel() < firstRange.mipEndExclusive()
                && firstRange.baseArrayLayer() < secondRange.arrayLayerEndExclusive()
                && secondRange.baseArrayLayer() < firstRange.arrayLayerEndExclusive();
    }
}
