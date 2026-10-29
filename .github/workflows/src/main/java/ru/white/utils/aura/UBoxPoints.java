package ru.white.utils.aura;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public final class UBoxPoints {
    private UBoxPoints() {}

    public static Vec3d getBestVector3dOnEntityBox(Box box) {
        return new Vec3d(
                (box.minX + box.maxX) * 0.5,
                (box.minY + box.maxY) * 0.5,
                (box.minZ + box.maxZ) * 0.5
        );
    }

    public static Vec3d getBestVector3dOnEntityBox(LivingEntity entity) {
        return getBestVector3dOnEntityBox(entity.getBoundingBox());
    }
}
