package ru.white.module.combat.aura.rotation;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import ru.white.manager.rotation.Rotation;
import ru.white.manager.rotation.RotationManager;
import ru.white.manager.rotation.RotationProcess;
import ru.white.module.combat.AttackAura;
import ru.white.module.combat.aura.RotationAura;
import ru.white.utils.aura.UBoxPoints;
import ru.white.utils.math.MathUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

public class FunTimeRotation implements RotationAura {

    private static final float DZ_NEAR_DEG = 3.0F;
    private static final float DZ_FAR_DEG = 15.0F;

    private static final int ANOMALY_WINDOW_MS = 30_000;
    private static final int ANOMALY_LIMIT = 8;
    private static final float MICRO_NOISE_DEG = 0.035F;
    private static final float MICRO_NOISE_PITCH = 0.025F;

    private static final long HIT_COOLDOWN_MS = 140L;
    private static final long HIT_JITTER_MS = 50L;
    private static final long AIM_ATTACK_GAP_MS = 80L;
    private static final long AIM_ATTACK_JIT_MS = 50L;
    private static final float AIM_SETTLE_DEG = 6F;
    private static final float FALLBACK_REACH = 3.0F;

    private LivingEntity lastTarget;

    private long nextJitterSwitch;

    private float lastYawJitter, lastPitchJitter;
    private float lastYawSpeed, lastPitchSpeed;

    private int snapsLeftOnTarget = 0;
    private long nextMicroNoiseAt = 0;
    private final Deque<Long> anomalyTimes = new ArrayDeque<>();

    private long nextAllowedAttackAt = 0;
    private long pendingAttackAt = 0;
    private boolean attackPending = false;

    private float yawAmp = 0F;

    // Отводка (имеет смысл в режиме SILENT: возвращает серверный угол к реальной камере)
    private boolean deflecting = false;
    private long deflectStartTime = 0L;
    private float deflectStartYaw = 0F;
    private float deflectStartPitch = 0F;

    private static long msNow() {
        return System.nanoTime() / 1_000_000L;
    }

    private float deadZone(float angleDeg) {
        if (angleDeg <= DZ_NEAR_DEG) return 0.05F;
        if (angleDeg >= DZ_FAR_DEG) return 1.0F;
        float t = (angleDeg - DZ_NEAR_DEG) / (DZ_FAR_DEG - DZ_NEAR_DEG);
        return 0.05F + 0.95F * t * t * (3F - 2F * t);
    }

    private void registerAnomaly(long now, boolean anomaly) {
        if (anomaly) anomalyTimes.addLast(now);
        while (!anomalyTimes.isEmpty() && now - anomalyTimes.peekFirst() > ANOMALY_WINDOW_MS) {
            anomalyTimes.pollFirst();
        }
    }

    private float aggressionFactor() {
        int n = anomalyTimes.size();
        if (n <= ANOMALY_LIMIT / 2) return 1.0F;
        if (n >= ANOMALY_LIMIT) return 0.55F;
        float t = (n - ANOMALY_LIMIT / 2F) / (ANOMALY_LIMIT / 2F);
        return 1.0F - 0.45F * t;
    }

    private void resetOnTargetChange() {
        attackPending = false;
        pendingAttackAt = 0;
        snapsLeftOnTarget = 0;
        lastYawJitter = 0F;
        lastPitchJitter = 0F;
        lastYawSpeed = 0F;
        lastPitchSpeed = 0F;
        nextMicroNoiseAt = 0;
        yawAmp = 0F;
    }

    private void resetOnTargetLost() {
        resetOnTargetChange();
        anomalyTimes.clear();
        lastTarget = null;
    }

    @Override
    public void reset() {
        resetOnTargetLost();
        deflecting = false;
        nextAllowedAttackAt = 0;
    }

    @Override
    public void onRotation(AttackAura aura, LivingEntity target, float[] ranges, boolean canAttack) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        if (!mc.player.isAlive()) { resetOnTargetLost(); return; }

        if (target == null || !target.isAlive()) {
            if (lastTarget != null) {
                Rotation startCur = RotationManager.get().current();
                deflecting = true;
                deflectStartYaw = startCur.yaw;
                deflectStartPitch = startCur.pitch;
                deflectStartTime = msNow();
                resetOnTargetLost();
            }

            if (deflecting) {
                long now = msNow();
                float duration = Math.max(1F, aura.deflectDuration.getFloat());
                float t = (now - deflectStartTime) / duration;

                if (t >= 1.0F) {
                    deflecting = false;
                    return;
                }

                float smooth = t * t * (3F - 2F * t);
                Rotation realRot = RotationManager.get().real();
                Rotation startRot = new Rotation(deflectStartYaw, deflectStartPitch);
                RotationProcess.update(startRot.lerp(realRot, smooth), 0, 0, 0, 0, 0, 1, false);
            }
            return;
        }

        deflecting = false;

        if (mc.player.isSubmergedInWater()) {
            attackPending = false;
            pendingAttackAt = 0;
            return;
        }

        long now = msNow();
        float maxDegTick = aura.maxStep.getFloat();
        float pitchSettle = aura.pitchSettle.getFloat();
        float yawMin = aura.yawJitterMin.getFloat();
        float yawMax = aura.yawJitterMax.getFloat();
        if (yawMax < yawMin) { float tmp = yawMin; yawMin = yawMax; yawMax = tmp; }

        if (target != lastTarget) {
            resetOnTargetChange();
            lastTarget = target;
            snapsLeftOnTarget = ThreadLocalRandom.current().nextInt(0, 3);
            nextJitterSwitch = now + ThreadLocalRandom.current().nextLong(1000L, 2001L);
            yawAmp = nextYawAmp(yawMin, yawMax);
        }

        Vec3d toTarget = UBoxPoints.getBestVector3dOnEntityBox(target)
                .subtract(mc.player.getEyePos());
        if (toTarget.lengthSquared() < 1e-6) return;
        Vec3d vec = toTarget.normalize();

        float rawYaw = (float) Math.toDegrees(Math.atan2(-vec.x, vec.z));
        float rawPitch = (float) MathHelper.clamp(
                -Math.toDegrees(Math.atan2(vec.y, Math.hypot(vec.x, vec.z))), -90F, 90F);

        if (!Float.isFinite(rawYaw) || !Float.isFinite(rawPitch)) return;

        Rotation cur = RotationManager.get().current();
        float curYaw = cur.yaw;
        float curPitch = cur.pitch;

        float dyaw = MathHelper.wrapDegrees(rawYaw - curYaw);
        float dpitch = rawPitch - curPitch;
        float angleToTarget = (float) Math.sqrt(dyaw * dyaw + dpitch * dpitch);

        float dz = deadZone(angleToTarget);
        float aggr = aggressionFactor();

        float targetYaw = rawYaw;
        float targetPitch = rawPitch;
        boolean doSnap = false;

        if (snapsLeftOnTarget > 0 && angleToTarget > 4F) {
            float allowedYaw = Math.min(Math.abs(dyaw), maxDegTick * aggr);
            float allowedPitch = Math.min(Math.abs(dpitch), maxDegTick * aggr * 0.7F);
            float overYaw = (float) ThreadLocalRandom.current().nextDouble(0.06, 0.20);
            float overPitch = (float) ThreadLocalRandom.current().nextDouble(0.03, 0.10);
            int signYaw = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
            int signPitch = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;

            targetYaw = curYaw + Math.copySign(allowedYaw, dyaw) * (1F + overYaw * signYaw);
            targetPitch = curPitch + Math.copySign(allowedPitch, dpitch) * (1F + overPitch * signPitch);

            doSnap = true;
            snapsLeftOnTarget--;
        }

        if (now >= nextJitterSwitch) {
            nextJitterSwitch = now + ThreadLocalRandom.current().nextLong(1000L, 2001L);
            yawAmp = nextYawAmp(yawMin, yawMax);
        }

        float jitterYaw = (float) (Math.cos(now / 90.0) * yawAmp) * dz * aggr;
        float jitterPitch = (float) (Math.sin(now / 90.0) * yawAmp) * dz * aggr * (pitchSettle / AIM_SETTLE_DEG);

        lastYawJitter += (jitterYaw - lastYawJitter) * 0.6F;
        lastPitchJitter += (jitterPitch - lastPitchJitter) * 0.6F;

        if (now >= nextMicroNoiseAt) {
            nextMicroNoiseAt = now + ThreadLocalRandom.current().nextLong(40L, 120L);
            lastYawJitter += (float) ThreadLocalRandom.current().nextGaussian() * MICRO_NOISE_DEG;
            lastPitchJitter += (float) ThreadLocalRandom.current().nextGaussian() * MICRO_NOISE_PITCH;
        }

        float sYaw = 40F;
        float sPitch = 12F;
        if (doSnap) {
            sYaw = maxDegTick * aggr;
            sPitch = maxDegTick * 0.7F * aggr;
        }

        lastYawSpeed += (sYaw - lastYawSpeed) * 0.5F;
        lastPitchSpeed += (sPitch - lastPitchSpeed) * 0.5F;

        // GCD-квантование делает RotationManager
        float finalYaw = targetYaw + lastYawJitter;
        float finalPitch = MathHelper.clamp(targetPitch + lastPitchJitter, -90F, 90F);

        boolean critNow = aura.critFlick.isEnabled() && needsCrit(mc) && angleToTarget <= AIM_SETTLE_DEG;
        if (critNow) finalPitch = MathHelper.clamp(finalPitch - 15F, -90F, 90F);

        Rotation applied = RotationProcess.update(new Rotation(finalYaw, finalPitch),
                lastYawSpeed + MathUtil.randomLerp(-2.5F, 2.5F),
                lastPitchSpeed + MathUtil.randomLerp(-1.0F, 1.0F),
                MathUtil.randomLerp(10.12F, 20.41F),
                MathUtil.randomLerp(10.12F, 20.41F),
                0, 1, false);

        float appliedYaw = applied.yaw;
        float appliedPitch = applied.pitch;

        // Прицел проверяем только по реально применённому углу (в SILENT камера не совпадает с ним)
        float targetDyaw = MathHelper.wrapDegrees(rawYaw - appliedYaw);
        float targetDpitch = rawPitch - appliedPitch;
        float targetAngle = (float) Math.sqrt(targetDyaw * targetDyaw + targetDpitch * targetDpitch);

        boolean pitchOk = Math.abs(targetDpitch) <= pitchSettle;
        boolean yawOk = Math.abs(targetDyaw) <= pitchSettle * 2F;

        boolean reachOk = inReach(mc, target, ranges);
        boolean aimOk = targetAngle <= AIM_SETTLE_DEG * 1.5F && pitchOk && yawOk;
        boolean cdOk = now >= nextAllowedAttackAt;

        if (attackPending) {
            if (!reachOk || !aimOk || !canAttack) {
                attackPending = false;
                pendingAttackAt = 0;
            } else if (now >= pendingAttackAt) {
                if (cdOk) {
                    aura.attack(target);
                    long cdNext = HIT_COOLDOWN_MS
                            + ThreadLocalRandom.current().nextLong(-HIT_JITTER_MS, HIT_JITTER_MS + 1);
                    nextAllowedAttackAt = now + Math.max(0L, cdNext);
                    attackPending = false;
                    pendingAttackAt = 0;
                } else {
                    pendingAttackAt = now + 25L;
                }
            }
        } else if (canAttack && reachOk && aimOk && cdOk) {
            attackPending = true;
            pendingAttackAt = now + AIM_ATTACK_GAP_MS
                    + ThreadLocalRandom.current().nextLong(-AIM_ATTACK_JIT_MS, AIM_ATTACK_JIT_MS + 1);
        }

        float stepYaw = MathHelper.wrapDegrees(appliedYaw - curYaw);
        boolean anomaly = doSnap && Math.abs(stepYaw) > maxDegTick * 0.9F;
        registerAnomaly(now, anomaly);
    }

    private static float nextYawAmp(float min, float max) {
        if (max <= min) return min;
        return min + ThreadLocalRandom.current().nextFloat() * (max - min);
    }

    /** Досягаемость считается до ближайшей точки хитбокса, как и фильтр цели в AttackAura. */
    private boolean inReach(MinecraftClient mc, LivingEntity target, float[] ranges) {
        float maxReach = FALLBACK_REACH;
        if (ranges != null && ranges.length > 0) {
            float m = 0F;
            for (float r : ranges) if (r > m) m = r;
            if (m > 0F) maxReach = m;
        }
        Vec3d eye = mc.player.getEyePos();
        Box b = target.getBoundingBox();
        Vec3d p = new Vec3d(
                MathHelper.clamp(eye.x, b.minX, b.maxX),
                MathHelper.clamp(eye.y, b.minY, b.maxY),
                MathHelper.clamp(eye.z, b.minZ, b.maxZ));
        return eye.distanceTo(p) <= maxReach;
    }

    private boolean needsCrit(MinecraftClient mc) {
        if (mc.player == null) return false;
        if (mc.player.isOnGround()) return false;
        if (mc.player.isTouchingWater() || mc.player.isInLava()) return false;
        if (mc.player.isClimbing()) return false;
        if (mc.player.hasStatusEffect(StatusEffects.BLINDNESS)) return false;
        return mc.player.fallDistance > 0F && !mc.player.isSprinting();
    }
}
