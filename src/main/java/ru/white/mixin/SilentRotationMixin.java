package ru.white.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.white.manager.rotation.Rotation;
import ru.white.manager.rotation.RotationManager;

/**
 * Подменяет yaw/pitch только на время отправки пакетов движения.
 * Нужен только для ApplyMode.SILENT. Добавьте в mixins.json в секцию "client".
 * Проверьте имя метода sendMovementPackets для 1.21.11 (Yarn).
 */
@Mixin(ClientPlayerEntity.class)
public abstract class SilentRotationMixin {
    @Unique private float white$realYaw;
    @Unique private float white$realPitch;
    @Unique private boolean white$spoofed;

    @Inject(method = "sendMovementPackets", at = @At("HEAD"))
    private void white$pre(CallbackInfo ci) {
        Rotation r = RotationManager.get().silentRotation();
        if (r == null) return;
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        white$realYaw = self.getYaw();
        white$realPitch = self.getPitch();
        self.setYaw(r.yaw);
        self.setPitch(r.pitch);
        white$spoofed = true;
    }

    @Inject(method = "sendMovementPackets", at = @At("RETURN"))
    private void white$post(CallbackInfo ci) {
        if (!white$spoofed) return;
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        self.setYaw(white$realYaw);
        self.setPitch(white$realPitch);
        white$spoofed = false;
    }
}
