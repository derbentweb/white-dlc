package ru.white.manager.rotation;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;

/** Привязка углов к минимальному шагу мыши (зависит от чувствительности). */
public final class GcdUtil {
    private GcdUtil() {}

    /** Минимальный шаг в градусах: f^3 * 8 * 0.15 (0.15 - множитель в Entity.changeLookDirection). */
    public static float step() {
        double sens = 0.5;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.options != null) {
            // Yarn 1.21: SimpleOption<Double>. При Mojang-маппингах: options.sensitivity().get()
            sens = mc.options.getMouseSensitivity().getValue();
        }
        sens = MathHelper.clamp(sens, 0.0, 1.0);
        double f = sens * 0.6 + 0.2;
        double step = f * f * f * 8.0 * 0.15;
        return step > 1.0E-4 ? (float) step : 0.01F;
    }

    /** Квантует РАЗНИЦУ (to - from) к сетке шага мыши. */
    public static Rotation quantize(Rotation from, Rotation to) {
        float step = step();
        float dYaw = MathHelper.wrapDegrees(to.yaw - from.yaw);
        float dPitch = to.pitch - from.pitch;
        float qYaw = Math.round(dYaw / step) * step;
        float qPitch = Math.round(dPitch / step) * step;
        return new Rotation(from.yaw + qYaw, MathHelper.clamp(from.pitch + qPitch, -90F, 90F));
    }
}
