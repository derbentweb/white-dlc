package ru.white.manager.rotation;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Единая точка управления поворотом.
 * Хранит текущий "серверный" угол, ограничивает скорость и ускорение,
 * учитывает приоритеты и применяет результат (камера или тихий режим).
 */
public final class RotationManager {
    private static final RotationManager INSTANCE = new RotationManager();
    private static final int IDLE_RESET_TICKS = 3;

    public static RotationManager get() {
        return INSTANCE;
    }

    public enum ApplyMode {
        /** Угол ставится прямо в игрока (камера поворачивается). Как работало раньше. */
        CAMERA,
        /** Камера не трогается, угол подменяется только при отправке пакетов (нужен SilentRotationMixin). */
        SILENT
    }

    // --- настройки (будущий GUI пишет сюда) ---
    private volatile ApplyMode mode = ApplyMode.CAMERA;
    private volatile boolean gcd = true;

    // --- состояние ---
    private Rotation server;
    private float lastYawStep, lastPitchStep;
    private int activePriority = Integer.MIN_VALUE;
    private int ticksLeft;
    private int idleTicks;

    private RotationManager() {}

    /** Вызвать один раз из onInitializeClient(). */
    public void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    // ---------------------------------------------------------------- API

    /**
     * @param yawSpeed/pitchSpeed максимум градусов за тик; <= 0 означает без ограничения
     * @param yawAccel/pitchAccel максимум изменения шага между тиками; <= 0 означает без ограничения
     * @param priority            процесс с меньшим приоритетом не перебьёт активный
     * @param ticks               сколько тиков запрос считается активным
     * @param force               игнорировать приоритет
     * @return реально применённый угол
     */
    public Rotation submit(Rotation target,
                           float yawSpeed, float pitchSpeed,
                           float yawAccel, float pitchAccel,
                           int priority, int ticks, boolean force) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || target == null || !target.isValid()) return current();
        if (!force && ticksLeft > 0 && priority < activePriority) return current();

        Rotation cur = current();
        Rotation goal = target.normalized();

        float dYaw = MathHelper.wrapDegrees(goal.yaw - cur.yaw);
        float dPitch = goal.pitch - cur.pitch;

        float sYaw = approach(dYaw, yawSpeed, yawAccel, lastYawStep);
        float sPitch = approach(dPitch, pitchSpeed, pitchAccel, lastPitchStep);

        Rotation next = new Rotation(cur.yaw + sYaw, cur.pitch + sPitch).normalized();
        if (gcd) next = GcdUtil.quantize(cur, next).normalized();

        lastYawStep = MathHelper.wrapDegrees(next.yaw - cur.yaw);
        lastPitchStep = next.pitch - cur.pitch;

        server = next;
        activePriority = priority;
        ticksLeft = Math.max(1, ticks);
        idleTicks = 0;

        if (mode == ApplyMode.CAMERA) {
            player.setYaw(next.yaw);
            player.setPitch(next.pitch);
        }
        return next;
    }

    /** Угол, от которого считать следующий шаг. */
    public Rotation current() {
        if (mode == ApplyMode.SILENT && server != null) return server;
        return real();
    }

    /** Реальный угол камеры игрока (то, куда смотрит мышь). */
    public Rotation real() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        return p == null ? Rotation.ZERO : new Rotation(p.getYaw(), p.getPitch());
    }

    /** Для миксина: угол для пакетов или null, если подмена не нужна. */
    public Rotation silentRotation() {
        return (mode == ApplyMode.SILENT && ticksLeft > 0) ? server : null;
    }

    public boolean isActive() {
        return ticksLeft > 0;
    }

    public ApplyMode getMode() { return mode; }
    public void setMode(ApplyMode mode) { this.mode = mode; }
    public boolean isGcd() { return gcd; }
    public void setGcd(boolean gcd) { this.gcd = gcd; }

    // ------------------------------------------------------------ internals

    private void tick() {
        if (ticksLeft > 0) {
            ticksLeft--;
            idleTicks = 0;
            if (ticksLeft == 0) activePriority = Integer.MIN_VALUE;
        } else if (++idleTicks > IDLE_RESET_TICKS) {
            server = null;
            lastYawStep = 0F;
            lastPitchStep = 0F;
        }
    }

    public void reset() {
        server = null;
        lastYawStep = lastPitchStep = 0F;
        activePriority = Integer.MIN_VALUE;
        ticksLeft = 0;
        idleTicks = 0;
    }

    private static float approach(float delta, float speed, float accel, float prevStep) {
        float step = speed > 0F ? MathHelper.clamp(delta, -speed, speed) : delta;
        if (accel > 0F) step = MathHelper.clamp(step, prevStep - accel, prevStep + accel);
        // не перелетать цель
        return delta >= 0F ? Math.min(step, delta) : Math.max(step, delta);
    }
}
