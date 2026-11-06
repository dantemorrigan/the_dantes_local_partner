package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Long-distance walking: the pathfinder only plans a few dozen blocks, so the partner walks toward far goals
 * through surface waypoints ~12 blocks apart, jumps gaps on the way, and veers off when blocked.
 */
final class Travel {
	private Vec3 lastPos = Vec3.ZERO;
	private int stuck, veer;
	private float veerAngle;

	/** One tick of travel toward {@code goal}. Returns false when it gave up (stuck for good). */
	boolean tick(PartnerBrain brain, ServerLevel level, Vec3 goal, double speed) {
		PartnerEntity p = brain.p;
		Vec3 to = goal.subtract(p.position()).multiply(1, 0, 1);
		double dist = to.length();
		if (dist < 1) return true;
		Vec3 dir = to.normalize();
		if (veerAngle != 0) dir = dir.yRot(veerAngle);
		Vec3 wp = dist < 14 ? goal : p.position().add(dir.scale(12));
		if (dist >= 14) {
			BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(wp));
			wp = Vec3.atBottomCenterOf(top);
		}
		if (p.tickCount % 20 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(wp.x, wp.y, wp.z, speed);
		brain.jumpIfBlocked(wp);
		if (p.tickCount % 40 == 0) {
			if (p.position().distanceToSqr(lastPos) < 2) {
				// Blocked: try going around (alternate sides, wider each time).
				stuck++;
				veerAngle = (float) Math.toRadians((stuck % 2 == 0 ? 1 : -1) * Math.min(90, 35 * ((stuck + 1) / 2)));
				veer = 6;
			} else if (veer > 0 && --veer == 0) {
				veerAngle = 0;
			}
			if (p.position().distanceToSqr(lastPos) >= 2) stuck = Math.max(0, stuck - 1);
			lastPos = p.position();
		}
		return stuck < 8;
	}
}
