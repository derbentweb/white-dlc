package ru.white.module.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import ru.white.manager.rotation.RotationManager;
import ru.white.module.Module;
import ru.white.module.setting.BooleanSetting;
import ru.white.module.setting.ModeSetting;
import ru.white.module.setting.NumberSetting;
import ru.white.module.combat.aura.RotationAura;
import ru.white.module.combat.aura.rotation.FunTimeRotation;

public class AttackAura extends Module {

    public final NumberSetting range = add(new NumberSetting("Дальность", 3.1, 1.0, 6.0, 0.1));
    public final NumberSetting fov = add(new NumberSetting("FOV", 90.0, 30.0, 180.0, 5.0));
    public final NumberSetting maxStep = add(new NumberSetting("Макс. шаг", 140.0, 30.0, 360.0, 10.0));
    public final NumberSetting pitchSettle = add(new NumberSetting("Pitch допуск", 5.0, 1.0, 30.0, 0.5));
    public final NumberSetting yawJitterMin = add(new NumberSetting("Yaw jitter мин", 3.0, 0.0, 20.0, 0.5));
    public final NumberSetting yawJitterMax = add(new NumberSetting("Yaw jitter макс", 7.0, 0.0, 30.0, 0.5));
    public final NumberSetting deflectDuration = add(new NumberSetting("Отводка (мс)", 150.0, 0.0, 500.0, 10.0));

    public final BooleanSetting critFlick = add(new BooleanSetting("Крит-флик", true));
    public final BooleanSetting targetPlayers = add(new BooleanSetting("Игроки", true));
    public final BooleanSetting targetMobs = add(new BooleanSetting("Мобы", false));
    public final ModeSetting priority = add(new ModeSetting("Приоритет", "Дистанция", "Дистанция", "Здоровье", "Угол"));

    // --- настройки ротации ---
    public final ModeSetting rotationMode = add(new ModeSetting("Ротация", "Камера", "Камера", "Тихая"));
    public final BooleanSetting gcdFix = add(new BooleanSetting("GCD-фикс", true));
    public final BooleanSetting stickyTarget = add(new BooleanSetting("Удержание цели", true));

    private final RotationAura rotation = new FunTimeRotation();
    private LivingEntity currentTarget;

    public AttackAura() {
        super("AttackAura", "Двухслойная аура: Polar + ИИ");
    }

    @Override
    public void onTick() {
        if (!isEnabled()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        RotationManager rm = RotationManager.get();
        rm.setMode("Тихая".equals(rotationMode.getValue())
                ? RotationManager.ApplyMode.SILENT
                : RotationManager.ApplyMode.CAMERA);
        rm.setGcd(gcdFix.isEnabled());

        LivingEntity target = findTarget(mc);
        currentTarget = target;

        float[] ranges = new float[] { range.getFloat() };
        boolean canAttack = mc.player.getAttackCooldownProgress(0.5f) >= 1.0f;

        rotation.onRotation(this, target, ranges, canAttack);
    }

    @Override
    protected void onDisable() {
        rotation.reset();
        RotationManager.get().reset();
        currentTarget = null;
    }

    /** Базовые фильтры без учёта дистанции и FOV. */
    private boolean isValid(MinecraftClient mc, LivingEntity living) {
        if (living == mc.player || !living.isAlive() || living.isRemoved()) return false;
        if (living.isSpectator() || living instanceof ArmorStandEntity) return false;
        if (living instanceof PlayerEntity) return targetPlayers.isEnabled();
        return targetMobs.isEnabled();
    }

    /** Расстояние от глаз до ближайшей точки хитбокса (согласовано с проверкой досягаемости). */
    private static double reachDistance(MinecraftClient mc, LivingEntity living) {
        Vec3d eye = mc.player.getEyePos();
        Box b = living.getBoundingBox();
        Vec3d p = new Vec3d(
                MathHelper.clamp(eye.x, b.minX, b.maxX),
                MathHelper.clamp(eye.y, b.minY, b.maxY),
                MathHelper.clamp(eye.z, b.minZ, b.maxZ));
        return eye.distanceTo(p);
    }

    private LivingEntity findTarget(MinecraftClient mc) {
        // Удержание: не переключаемся, пока текущая цель в пределах дальности (+ гистерезис)
        if (stickyTarget.isEnabled() && currentTarget != null
                && isValid(mc, currentTarget)
                && reachDistance(mc, currentTarget) <= range.getValue() + 0.3) {
            return currentTarget;
        }

        LivingEntity best = null;
        double bestVal = Double.MAX_VALUE;
        double fovRad = Math.toRadians(fov.getValue());

        for (var e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity living) || !isValid(mc, living)) continue;

            double d = reachDistance(mc, living);
            if (d > range.getValue()) continue;

            var toTarget = living.getBoundingBox().getCenter().subtract(mc.player.getEyePos());
            if (toTarget.lengthSquared() < 1e-6) continue;
            var look = mc.player.getRotationVec(1.0f);
            double angle = Math.acos(Math.max(-1, Math.min(1, look.dotProduct(toTarget.normalize()))));
            if (angle > fovRad / 2.0) continue;

            double v;
            switch (priority.getValue()) {
                case "Здоровье" -> v = living.getHealth();
                case "Угол" -> v = angle;
                default -> v = d;
            }
            if (v < bestVal) { bestVal = v; best = living; }
        }
        return best;
    }

    public void attack(LivingEntity target) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager == null || mc.player == null) return;
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
    }
}
