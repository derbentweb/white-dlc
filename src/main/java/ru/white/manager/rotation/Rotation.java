package ru.white.manager.rotation;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

public class Rotation {
    public static final Rotation ZERO = new Rotation(0f, 0f);

    public final float yaw;
    public final float pitch;

    public Rotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** Угол от глаз до точки. */
    public static Rotation from(Vec3d eye, Vec3d target) {
        Vec3d d = target.subtract(eye);
        double flat = Math.hypot(d.x, d.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, flat));
        return new Rotation(yaw, pitch).normalized();
    }

    /** yaw в [-180; 180), pitch в [-90; 90]. */
    public Rotation normalized() {
        return new Rotation(MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -90f, 90f));
    }

    /** Кратчайшая разница (this -> other). */
    public Rotation deltaTo(Rotation other) {
        return new Rotation(MathHelper.wrapDegrees(other.yaw - yaw), other.pitch - pitch);
    }

    public float distanceTo(Rotation other) {
        Rotation d = deltaTo(other);
        return (float) Math.hypot(d.yaw, d.pitch);
    }

    public Rotation add(float dYaw, float dPitch) {
        return new Rotation(yaw + dYaw, pitch + dPitch);
    }

    /** Интерполяция по кратчайшему пути, t в [0; 1]. */
    public Rotation lerp(Rotation to, float t) {
        Rotation d = deltaTo(to);
        return new Rotation(yaw + d.yaw * t, pitch + d.pitch * t).normalized();
    }

    /** Привязка к шагу мыши относительно предыдущего угла. */
    public Rotation withGcd(Rotation previous) {
        return GcdUtil.quantize(previous, this);
    }

    public boolean isValid() {
        return Float.isFinite(yaw) && Float.isFinite(pitch);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Rotation r
                && Float.compare(yaw, r.yaw) == 0
                && Float.compare(pitch, r.pitch) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(yaw, pitch);
    }

    @Override
    public String toString() {
        return "Rotation[yaw=" + yaw + ", pitch=" + pitch + "]";
    }
}
