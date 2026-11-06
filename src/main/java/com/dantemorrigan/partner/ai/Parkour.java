package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Running jumps over gaps the pathfinder cannot cross, with the numbers of a real player:
 * a sprint-jump starts at 0.42 up and carries about 0.53 blocks/tick forward, air drag 0.91 per tick, so the
 * partner clears up to 3 empty blocks on the same level, 2 when landing one block higher, and drops of up to
 * 2 blocks on the far side. It walks up to the edge first (the run-up), faces the landing, then jumps.
 */
public final class Parkour {
	/** Max horizontal speed of a sprint-jump (player: ~0.28 sprint + 0.2 jump boost, plus momentum). */
	private static final double MAX_V = 0.56;
	private boolean airborne;
	private int airTicks, cooldown, runUp;
	private @Nullable Vec3 runUpTo;
	private @Nullable Vec3 jumpVelocity;
	private @Nullable BlockPos pendingLand;
	private int pendingDx, pendingDz;

	public boolean airborne() {
		return airborne;
	}

	/** Called every tick: keeps a jump going until it lands. True while in the air (the brain then waits). */
	public boolean tickAir(PartnerEntity p) {
		if (cooldown > 0) cooldown--;
		if (runUpTo != null) {
			// The run-up: walk to the very edge, then jump.
			if (horizontal(p.position(), runUpTo) < 0.03 || ++runUp > 15) {
				runUpTo = null;
				runUp = 0;
				if (pendingLand != null && p.onGround()) launch(p, pendingLand, pendingDx, pendingDz);
				pendingLand = null;
				return airborne;
			}
			p.getMoveControl().setWantedPosition(runUpTo.x, runUpTo.y, runUpTo.z, PartnerEntity.SPRINT);
			return true;
		}
		if (!airborne) return false;
		airTicks++;
		if (airTicks <= 2 && jumpVelocity != null) p.setDeltaMovement(jumpVelocity.x, p.getDeltaMovement().y, jumpVelocity.z);
		if (airTicks > 2 && (p.onGround() || p.isInWater() || p.onClimbable()) || airTicks > 40) {
			airborne = false;
			jumpVelocity = null;
			p.setSprinting(false);
			cooldown = 10;
			return false;
		}
		return true;
	}

	/**
	 * Tries a jump toward {@code target} from where the partner stands. Use when walking is blocked (no full path).
	 * Returns true when a jump (or its run-up) started.
	 */
	public boolean tryJump(PartnerEntity p, ServerLevel level, Vec3 target) {
		if (airborne || cooldown > 0 || runUpTo != null || !p.onGround() || p.isInWater()) return false;
		Vec3 to = target.subtract(p.position());
		if (to.horizontalDistanceSqr() < 4) return false;
		// Snap to one of 8 directions.
		double angle = Math.atan2(to.z, to.x);
		int step = Math.floorMod((int) Math.round(angle / (Math.PI / 4)), 8);
		int dx = new int[]{1, 1, 0, -1, -1, -1, 0, 1}[step];
		int dz = new int[]{0, 1, 1, 1, 0, -1, -1, -1}[step];
		BlockPos feet = p.blockPosition();
		// Only from an edge: the block right ahead must have no floor.
		BlockPos ahead = feet.offset(dx, 0, dz);
		if (solidFloor(level, ahead) || !passable(level, ahead) || !passable(level, ahead.above())) return false;
		boolean diagonal = dx != 0 && dz != 0;
		int maxK = diagonal ? 3 : 4;
		int wantDy = (int) Math.signum(Math.round(target.y - p.getY()));
		Landing best = null;
		for (int k = 2; k <= maxK && best == null; k++) {
			if (!arcClear(level, feet, dx, dz, k)) break;
			for (int dy : wantDy > 0 ? new int[]{1, 0, -1, -2} : new int[]{0, -1, 1, -2}) {
				BlockPos land = feet.offset(dx * k, dy, dz * k);
				if (!solidFloor(level, land) || !passable(level, land) || !passable(level, land.above())) continue;
				if (dy > 0 && !passable(level, feet.above(2))) continue; // head room for the jump up
				double dist = Vec3.atBottomCenterOf(land).subtract(p.position()).horizontalDistance();
				double v = dist / airSum(dy);
				if (v > MAX_V) continue;
				best = new Landing(land, v);
				break;
			}
		}
		if (best == null) return false;
		// Run-up: stand right at the edge first (players jump from the very last pixel of a block).
		Vec3 edge = Vec3.atBottomCenterOf(feet).add(dx * 0.4, 0, dz * 0.4);
		p.getNavigation().stop();
		if (horizontal(p.position(), edge) > 0.03) {
			runUpTo = edge;
			runUp = 1;
			pendingLand = best.land;
			pendingDx = dx;
			pendingDz = dz;
			return true;
		}
		launch(p, best.land, dx, dz);
		return true;
	}

	private void launch(PartnerEntity p, BlockPos land, int dx, int dz) {
		int dy = land.getY() - p.blockPosition().getY();
		double dist = Vec3.atBottomCenterOf(land).subtract(p.position()).horizontalDistance();
		double v = Math.min(MAX_V, dist / airSum(dy));
		Vec3 dir = new Vec3(dx, 0, dz).normalize();
		jumpVelocity = dir.scale(v);
		p.setYRot((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f);
		p.setYHeadRot(p.getYRot());
		p.setSprinting(true);
		p.setDeltaMovement(jumpVelocity.x, 0.42 + (dy > 0 ? 0.04 : 0), jumpVelocity.z);
		p.hurtMarked = true;
		airborne = true;
		airTicks = 0;
	}

	/** Resets a pending run-up (e.g. when the brain switches to something else). */
	public void cancel() {
		runUpTo = null;
		runUp = 0;
		pendingLand = null;
	}

	private record Landing(BlockPos land, double v) {}

	/** Sum of the air-drag series over the flight time: horizontal distance = v0 * airSum. */
	private static double airSum(int dy) {
		return switch (dy) {
			case 1 -> 5.0;
			case 0 -> 7.4;
			case -1 -> 8.4;
			default -> 9.2;
		};
	}

	private static boolean arcClear(ServerLevel level, BlockPos feet, int dx, int dz, int k) {
		for (int i = 1; i < k; i++) {
			BlockPos b = feet.offset(dx * i, 0, dz * i);
			if (!passable(level, b) || !passable(level, b.above()) || !passable(level, b.above(2))) return false;
			if (dx != 0 && dz != 0) {
				// Diagonal: the corners must be open too, or the jump clips them.
				if (!passable(level, feet.offset(dx * i, 1, dz * (i - 1))) || !passable(level, feet.offset(dx * (i - 1), 1, dz * i))) return false;
			}
		}
		return true;
	}

	static boolean passable(ServerLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty();
	}

	static boolean solidFloor(ServerLevel level, BlockPos feet) {
		BlockPos below = feet.below();
		BlockState s = level.getBlockState(below);
		return s.isFaceSturdy(level, below, Direction.UP) && s.getFluidState().isEmpty()
				&& !s.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) && !s.is(net.minecraft.world.level.block.Blocks.CAMPFIRE);
	}

	private static double horizontal(Vec3 a, Vec3 b) {
		double x = a.x - b.x, z = a.z - b.z;
		return x * x + z * z;
	}
}
