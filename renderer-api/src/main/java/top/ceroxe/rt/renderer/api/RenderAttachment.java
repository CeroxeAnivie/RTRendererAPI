package top.ceroxe.rt.renderer.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable load/store declaration for one renderable texture aspect.
 *
 * @param view exact versioned attachment view
 * @param loadOperation initial-content operation
 * @param storeOperation final-content operation
 * @param clearValue present exactly when {@code loadOperation} is {@link LoadOp#CLEAR}
 */
public record RenderAttachment(
        TextureView view,
        LoadOp loadOperation,
        StoreOp storeOperation,
        Optional<ClearValue> clearValue
) {
    /** Validates attachment role, aspect, and clear semantics. */
    public RenderAttachment {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(loadOperation, "loadOperation");
        Objects.requireNonNull(storeOperation, "storeOperation");
        clearValue = Objects.requireNonNull(clearValue, "clearValue");
        if (view.range().mipLevelCount() != 1) {
            throw new IllegalArgumentException("render attachment views must address exactly one mip level");
        }
        TextureAspect aspect = view.range().aspect();
        TextureUsage requiredUsage = aspect == TextureAspect.COLOR
                ? TextureUsage.COLOR_ATTACHMENT
                : TextureUsage.DEPTH_STENCIL_ATTACHMENT;
        if (!view.texture().usage().contains(requiredUsage)) {
            throw new IllegalArgumentException("attachment texture does not declare required usage: " + requiredUsage);
        }
        if (loadOperation == LoadOp.CLEAR) {
            ClearValue value = clearValue.orElseThrow(
                    () -> new IllegalArgumentException("clear load operation requires a clear value")
            );
            if (value.aspect() != aspect) {
                throw new IllegalArgumentException("clear value aspect does not match attachment view aspect");
            }
        } else if (clearValue.isPresent()) {
            throw new IllegalArgumentException("clear value is valid only for the clear load operation");
        }
    }

    /** Creates a non-clearing attachment declaration. */
    public static RenderAttachment of(TextureView view, LoadOp loadOperation, StoreOp storeOperation) {
        if (loadOperation == LoadOp.CLEAR) {
            throw new IllegalArgumentException("use cleared() for the clear load operation");
        }
        return new RenderAttachment(view, loadOperation, storeOperation, Optional.empty());
    }

    /** Creates a clearing attachment declaration. */
    public static RenderAttachment cleared(TextureView view, StoreOp storeOperation, ClearValue clearValue) {
        return new RenderAttachment(view, LoadOp.CLEAR, storeOperation, Optional.of(
                Objects.requireNonNull(clearValue, "clearValue")
        ));
    }
}
