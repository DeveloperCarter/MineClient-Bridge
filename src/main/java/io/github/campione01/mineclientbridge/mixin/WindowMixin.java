package io.github.campione01.mineclientbridge.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.campione01.mineclientbridge.IsolatedNativeCallbacks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void mineclientBridge$detachOwnedCallbacks(CallbackInfo callback) {
        RenderSystem.assertOnRenderThread();
        IsolatedNativeCallbacks.prepareClose(((Window) (Object) this).getWindow());
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void mineclientBridge$releaseDetachedCallbacks(CallbackInfo callback) {
        IsolatedNativeCallbacks.release(((Window) (Object) this).getWindow());
    }
}
