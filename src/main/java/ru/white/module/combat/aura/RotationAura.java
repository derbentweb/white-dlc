package ru.white.module.combat.aura;

import net.minecraft.entity.LivingEntity;
import ru.white.module.combat.AttackAura;

public interface RotationAura {
    void onRotation(AttackAura aura, LivingEntity target, float[] ranges, boolean canAttack);

    /** Сброс внутреннего состояния (вызывается при выключении модуля). */
    default void reset() {}
}
