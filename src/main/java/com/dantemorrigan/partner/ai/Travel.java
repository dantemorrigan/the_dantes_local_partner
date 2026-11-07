package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.Territory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Smart long-distance navigation and obstacle handling:
 * <ul>
 *   <li><b>Dry Contour Bypass:</b> Scans rays ahead to smoothly walk around ponds, cliffs, lava, and dense obstacles on dry land.</li>
 *   <li><b>Straight-Line Causeway:</b> When a river or chasm must be crossed (up to 24 blocks wide), locks a strict 1-block-wide straight line toward the goal and steps onto each block as it places it.</li>
 *   <li><b>Surface Sprint-Swimming:</b> Across wide lakes/oceans (&gt; 24 blocks) or when already in water, sprint-swims in a straight line to the far shore without placing chaotic blocks in the water.</li>
 *   <li><b>Self-Rescue on Land:</b> Clears blocking foliage immediately, jumps ledges, and digs a clean 1×2 staircase or pillars out of pits.</li>
 * </ul>
 */
public final class Travel {
	private static final int MAX_BRIDGE_SPAN = 24;

	private Vec3 lastPos = Vec3.ZERO;
	private int stuck;
	private float bypassAngle;
	private int bypassTicks;
	private int bypassSign = 1;

	// Locked straight-line bridge state so water crossings are always a clean 1-block-wide line
	private @Nullable BlockPos bridgeOrigin;
	private @Nullable BlockPos bridgeTip;
	private Vec3 bridgeDir = Vec3.ZERO;
	private int bridgeSteps;

	/** One tick of travel toward {@code goal}. Returns false when it had to call SOS / evacuate after all self-rescue tries. */
	public boolean tick(PartnerBrain brain, ServerLevel level, Vec3 goal, double speed) {
		PartnerEntity p = brain.p;
		Vec3 to = goal.subtract(p.position()).multiply(1, 0, 1);
		double dist = to.length();
		if (dist < 1.0) {
			clearBridge();
			return true;
		}
		Vec3 straightDir = to.normalize();

		// 1. Active straight-line bridge across narrow/medium water or chasm
		if (bridgeOrigin != null && bridgeTip != null) {
			if (!p.isInWater() && bridgeSteps <= MAX_BRIDGE_SPAN + 4
					&& PartnerBrain.horizontalDistSqr(p.position(), Vec3.atBottomCenterOf(bridgeTip)) <= 9.0) {
				if (tickStraightBridge(brain, level, speed)) {
					updateProgress(p);
					return true;
				}
			}
			clearBridge();
		}

		// 2. In water: swim straight across the surface toward the goal (never place random blocks while swimming)
		if (p.isInWater()) {
			clearBridge();
			swimStraight(brain, level, straightDir, goal, dist);
			updateProgress(p);
			return true;
		}

		// 3. Scan ahead for water, lava, or steep cliffs and find a dry land bypass if one exists
		if (isDirectLineBlocked(level, p.position(), straightDir, Math.min(dist, 12.0))) {
			Float detour = findDryBypassAngle(level, p.position(), straightDir, Math.min(dist, 14.0));
			if (detour != null) {
				bypassAngle = detour;
				bypassSign = detour >= 0 ? 1 : -1;
				bypassTicks = 25;
			} else {
				// No dry detour within ±80°: face the obstacle straight on
				bypassAngle = 0;
				bypassTicks = 0;
				if (tryHandleFrontWaterOrChasm(brain, level, straightDir, dist, speed)) {
					updateProgress(p);
					return true;
				}
			}
		} else if (bypassTicks > 0) {
			bypassTicks--;
			if (bypassTicks == 0) bypassAngle = 0;
		} else {
			bypassAngle = 0;
		}

		Vec3 moveDir = bypassAngle != 0 ? straightDir.yRot(bypassAngle) : straightDir;

		// Proactively clear leaves, bushes, or cobwebs right in front so forests never stall movement
		clearFoliageAhead(brain, level, moveDir);

		Vec3 wp = (dist < 14 && bypassAngle == 0) ? goal : p.position().add(moveDir.scale(Math.min(dist, 12.0)));
		if (dist >= 14 || bypassAngle != 0) {
			BlockPos top = findOpenSurface(level, BlockPos.containing(wp));
			wp = Vec3.atBottomCenterOf(top);
		}
		if (p.tickCount % 12 == 0 || p.getNavigation().isDone()) {
			p.getNavigation().moveTo(wp.x, wp.y, wp.z, speed);
		}
		brain.jumpIfBlocked(wp);

		// Active land self-rescue while stuck: dig staircase up a cliff or pillar out of a pit
		if (stuck >= 1 && p.tickCount % 8 == 0) {
			selfRescueLand(brain, level, straightDir, wp);
		}

		if (p.tickCount % 20 == 0) {
			if (p.position().distanceToSqr(lastPos) < 1.0) {
				stuck++;
				// Alternate preferred contour side if stuck on terrain
				if (stuck % 2 == 0) bypassSign = -bypassSign;
				if (stuck == 4) brain.report("self_unstuck");
			} else {
				stuck = Math.max(0, stuck - 1);
			}
			lastPos = p.position();
		}
		if (stuck >= 8) {
			stuck = 0;
			clearBridge();
			brain.emergencyUnstuck(level);
			return false;
		}
		return true;
	}

	private void updateProgress(PartnerEntity p) {
		if (p.tickCount % 20 == 0) {
			if (p.position().distanceToSqr(lastPos) >= 0.6) {
				stuck = Math.max(0, stuck - 1);
			}
			lastPos = p.position();
		}
	}

	private void clearBridge() {
		bridgeOrigin = null;
		bridgeTip = null;
		bridgeSteps = 0;
	}

	/** Checks whether the straight line over the next few blocks hits water, lava, a sheer wall, or a deep drop. */
	private static boolean isDirectLineBlocked(ServerLevel level, Vec3 start, Vec3 dir, double maxDist) {
		int prevY = BlockPos.containing(start).getY();
		for (double d = 2.5; d <= maxDist; d += 2.5) {
			BlockPos sample = BlockPos.containing(start.x + dir.x * d, start.y, start.z + dir.z * d);
			if (!level.hasChunkAt(sample)) break;
			BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sample);
			if (!level.getFluidState(top).isEmpty() || !level.getFluidState(top.below()).isEmpty()) {
				return true;
			}
			if (Math.abs(top.getY() - prevY) > 3) {
				return true;
			}
			prevY = top.getY();
		}
		return false;
	}

	/**
	 * Scans rays from ±20° to ±80° around {@code straightDir} to find a dry, walkable contour around water or cliffs.
	 * Returns the best detour angle in radians, or {@code null} if all forward rays are blocked.
	 */
	private @Nullable Float findDryBypassAngle(ServerLevel level, Vec3 start, Vec3 straightDir, double lookAhead) {
		int[] degs = {20, 35, 50, 65, 80};
		for (int deg : degs) {
			for (int sign : new int[]{bypassSign, -bypassSign}) {
				float rad = (float) Math.toRadians(deg * sign);
				Vec3 ray = straightDir.yRot(rad);
				if (isRayDryAndWalkable(level, start, ray, lookAhead)) {
					return rad;
				}
			}
		}
		return null;
	}

	private static boolean isRayDryAndWalkable(ServerLevel level, Vec3 start, Vec3 ray, double lookAhead) {
		int prevY = BlockPos.containing(start).getY();
		double maxD = Math.max(4.0, Math.min(lookAhead, 12.0));
		for (double d = 3.0; d <= maxD; d += 3.0) {
			BlockPos sample = BlockPos.containing(start.x + ray.x * d, start.y, start.z + ray.z * d);
			if (!level.hasChunkAt(sample)) return false;
			BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sample);
			if (!level.getFluidState(top).isEmpty() || !level.getFluidState(top.below()).isEmpty()) {
				return false;
			}
			if (Math.abs(top.getY() - prevY) > 2) {
				return false;
			}
			if (level.getBlockState(top).is(BlockTags.LOGS)) {
				return false;
			}
			prevY = top.getY();
		}
		return true;
	}

	/**
	 * When no dry bypass exists and water or a drop is right in front of the partner:
	 * - If the span across is &lt;= {@link #MAX_BRIDGE_SPAN}, starts a locked straight-line 1-block bridge.
	 * - If the water is wider (large lake / ocean), steps directly into the water to sprint-swim straight across.
	 */
	private boolean tryHandleFrontWaterOrChasm(PartnerBrain brain, ServerLevel level, Vec3 straightDir, double distToGoal, double speed) {
		PartnerEntity p = brain.p;
		BlockPos feet = p.blockPosition();
		Direction stepDir = Direction.getApproximateNearest(straightDir.x, 0, straightDir.z);
		BlockPos ahead = feet.relative(stepDir);
		BlockPos aheadBelow = ahead.below();

		boolean waterAhead = !level.getFluidState(aheadBelow).isEmpty() || !level.getFluidState(ahead).isEmpty();
		boolean chasmAhead = level.getBlockState(aheadBelow).isAir() && level.getBlockState(aheadBelow.below()).isAir();
		if (!waterAhead && !chasmAhead) return false;

		int span = measureCrossingSpan(level, feet, straightDir, MAX_BRIDGE_SPAN + 4);
		if (waterAhead && span > 6) {
			// Water body wider than a small stream: dive in and fast-swim straight across!
			swimStraight(brain, level, straightDir, p.position().add(straightDir.scale(Math.min(distToGoal, 16.0))), distToGoal);
			return true;
		}

		// Start a straight 1-block-wide bridge locked to straightDir
		int bridgeY = feet.getY() - 1;
		if (!level.getFluidState(ahead).isEmpty()) {
			bridgeY = ahead.getY();
		}
		bridgeOrigin = new BlockPos(feet.getX(), bridgeY, feet.getZ());
		bridgeTip = bridgeOrigin;
		bridgeDir = straightDir;
		bridgeSteps = 0;
		return tickStraightBridge(brain, level, speed);
	}

	/** Measures how many blocks of water or air chasm lie ahead along {@code dir} before solid dry ground resumes. */
	private static int measureCrossingSpan(ServerLevel level, BlockPos feet, Vec3 dir, int maxCheck) {
		int span = 0;
		for (int d = 1; d <= maxCheck; d++) {
			BlockPos sample = BlockPos.containing(feet.getX() + 0.5 + dir.x * d, feet.getY(), feet.getZ() + 0.5 + dir.z * d);
			if (!level.hasChunkAt(sample)) return maxCheck + 1;
			BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sample);
			boolean wet = !level.getFluidState(top).isEmpty() || !level.getFluidState(top.below()).isEmpty();
			boolean chasm = feet.getY() - top.getY() >= 4;
			if (wet || chasm) {
				span = d;
			} else if (span > 0) {
				return span;
			}
		}
		return maxCheck + 1;
	}

	/**
	 * Executes one tick of straight-line 1-block-wide bridging.
	 * Uses a 4-connected Bresenham step along {@link #bridgeDir} so blocks only ever form a single straight line.
	 */
	private boolean tickStraightBridge(PartnerBrain brain, ServerLevel level, double speed) {
		PartnerEntity p = brain.p;
		if (bridgeOrigin == null || bridgeTip == null) return false;

		// Walk onto the current bridge tip first before extending the next block
		Vec3 tipStand = new Vec3(bridgeTip.getX() + 0.5, bridgeTip.getY() + 1.0, bridgeTip.getZ() + 0.5);
		if (PartnerBrain.horizontalDistSqr(p.position(), tipStand) > 1.3 * 1.3) {
			p.getNavigation().stop();
			p.getLookControl().setLookAt(tipStand.x, tipStand.y + 1.0, tipStand.z, 30f, 30f);
			p.getMoveControl().setWantedPosition(tipStand.x, tipStand.y, tipStand.z, PartnerEntity.WALK);
			if (tipStand.y > p.getY() + 0.2 && p.onGround()) {
				p.getJumpControl().jump();
			}
			return true;
		}

		BlockPos next = nextBresenhamStep(bridgeOrigin, bridgeTip, bridgeDir);
		if (Territory.isProtected(level, next)) return false;

		// Clear any leaves or plants above the next bridge block
		for (BlockPos clear : new BlockPos[]{next.above(), next.above(2)}) {
			BlockState s = level.getBlockState(clear);
			if (!s.isAir() && (s.is(BlockTags.LEAVES) || s.canBeReplaced())) {
				level.destroyBlock(clear, true, p);
			}
		}

		BlockState nextState = level.getBlockState(next);
		boolean needsBlock = nextState.isAir() || !nextState.getFluidState().isEmpty() || nextState.canBeReplaced();
		if (!needsBlock) {
			// Reached solid dry ground on the far side! Step onto it and finish bridging.
			Vec3 landPos = new Vec3(next.getX() + 0.5, next.getY() + 1.0, next.getZ() + 0.5);
			p.getNavigation().stop();
			p.getMoveControl().setWantedPosition(landPos.x, landPos.y, landPos.z, speed);
			if (landPos.y > p.getY() + 0.2 && p.onGround()) {
				p.getJumpControl().jump();
			}
			return false;
		}

		placeScaffold(p, level, next);
		bridgeTip = next;
		bridgeSteps++;

		Vec3 nextStand = new Vec3(next.getX() + 0.5, next.getY() + 1.0, next.getZ() + 0.5);
		p.getNavigation().stop();
		p.getLookControl().setLookAt(nextStand.x, nextStand.y + 1.0, nextStand.z, 30f, 30f);
		p.getMoveControl().setWantedPosition(nextStand.x, nextStand.y, nextStand.z, PartnerEntity.WALK);
		if (nextStand.y > p.getY() + 0.2 && p.onGround()) {
			p.getJumpControl().jump();
		}
		return true;
	}

	/**
	 * Picks the single 4-connected horizontal neighbor of {@code tip} that moves forward along {@code dir}
	 * while minimizing perpendicular distance to the ideal line from {@code origin} along {@code dir}.
	 */
	private static BlockPos nextBresenhamStep(BlockPos origin, BlockPos tip, Vec3 dir) {
		BlockPos best = tip.relative(Direction.getApproximateNearest(dir.x, 0, dir.z));
		double bestErr = Double.MAX_VALUE;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			double dot = d.getStepX() * dir.x + d.getStepZ() * dir.z;
			if (dot <= 0.05) continue;
			BlockPos cand = tip.relative(d);
			double relX = cand.getX() - origin.getX();
			double relZ = cand.getZ() - origin.getZ();
			// Perpendicular distance from cand to the line (origin + t * dir)
			double perp = Math.abs(relX * (-dir.z) + relZ * dir.x);
			if (perp < bestErr) {
				bestErr = perp;
				best = cand;
			}
		}
		return best;
	}

	/** Swims straight across water at high speed on the surface and climbs out cleanly onto the far bank. */
	private void swimStraight(PartnerBrain brain, ServerLevel level, Vec3 straightDir, Vec3 goal, double dist) {
		PartnerEntity p = brain.p;
		p.getNavigation().stop();
		p.setSwimming(true);
		p.setSprinting(true);
		p.getJumpControl().jump();
		Vec3 swimWp = dist < 14.0 ? goal : p.position().add(straightDir.scale(12.0));
		p.getLookControl().setLookAt(swimWp.x, p.getEyeY(), swimWp.z, 30f, 30f);
		p.getMoveControl().setWantedPosition(swimWp.x, p.getY() + 0.6, swimWp.z, PartnerEntity.CATCH_UP);

		Vec3 vel = p.getDeltaMovement();
		p.setDeltaMovement(straightDir.x * 0.28, Math.max(vel.y, 0.04), straightDir.z * 0.28);

		// If right against a shore bank, hop up or clear blocking shore foliage/dirt at head height
		Direction facing = Direction.getApproximateNearest(straightDir.x, 0, straightDir.z);
		BlockPos ahead = p.blockPosition().relative(facing);
		if (!level.getBlockState(ahead).isAir() && level.getBlockState(ahead.above()).isAir() && level.getBlockState(ahead.above(2)).isAir()) {
			p.setDeltaMovement(straightDir.x * 0.32, 0.42, straightDir.z * 0.32);
		}
		BlockState aheadTop = level.getBlockState(ahead.above());
		if (!aheadTop.isAir() && !Territory.isProtected(level, ahead.above())
				&& (aheadTop.is(BlockTags.LEAVES) || WorldScan.diggable(aheadTop))) {
			brain.breaker.tick(p, level, ahead.above());
		}
	}

	/** Breaks leaves, cobwebs, or bushes directly blocking the walking path so forests don't cause detours. */
	private static void clearFoliageAhead(PartnerBrain brain, ServerLevel level, Vec3 dir) {
		PartnerEntity p = brain.p;
		Direction facing = Direction.getApproximateNearest(dir.x, 0, dir.z);
		BlockPos ahead = p.blockPosition().relative(facing);
		for (BlockPos check : new BlockPos[]{ahead.above(), ahead}) {
			BlockState s = level.getBlockState(check);
			if (!s.isAir() && !Territory.isProtected(level, check)
					&& (s.is(BlockTags.LEAVES) || s.is(Blocks.COBWEB) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.BAMBOO))) {
				level.destroyBlock(check, true, p);
				p.swing(InteractionHand.MAIN_HAND);
				return;
			}
		}
	}

	private static BlockPos findOpenSurface(ServerLevel level, BlockPos approx) {
		BlockPos best = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, approx);
		for (int r = 0; r <= 4; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos cand = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, approx.offset(dx, 0, dz));
					if (level.getFluidState(cand).isEmpty() && level.getFluidState(cand.below()).isEmpty()
							&& !level.getBlockState(cand).is(BlockTags.LOGS) && !level.getBlockState(cand.above()).is(BlockTags.LEAVES)) {
						return cand;
					}
				}
			}
		}
		return best;
	}

	/** Land self-rescue when genuinely trapped in a pit or against an unavoidable cliff wall. */
	private void selfRescueLand(PartnerBrain brain, ServerLevel level, Vec3 straightDir, Vec3 wp) {
		PartnerEntity p = brain.p;
		if (p.isInWater()) return;
		Direction facing = Direction.getApproximateNearest(straightDir.x, 0, straightDir.z);
		BlockPos feet = p.blockPosition();
		BlockPos ahead = feet.relative(facing);

		// 1. Trapped in a pit or deep below the target waypoint: dig steps upward first, or pillar straight up.
		int surfaceY = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet).getY();
		if (wp.y - p.getY() >= 2.5 || surfaceY - feet.getY() >= 3) {
			if (tryDigStepUp(brain, level, feet, facing)) return;
			if (tryPillarUp(p, level, feet)) return;
		}

		// 2. Blocked by leaves, cobwebs, dirt or stone right in front: break a clean 1×2 passage (never inside player builds).
		for (BlockPos check : new BlockPos[]{ahead.above(), ahead, feet.above(2)}) {
			BlockState s = level.getBlockState(check);
			if (!s.isAir() && !Territory.isProtected(level, check)
					&& (s.is(BlockTags.LEAVES) || s.is(Blocks.COBWEB) || WorldScan.diggable(s))) {
				brain.breaker.tick(p, level, check);
				return;
			}
		}
	}

	private boolean tryPillarUp(PartnerEntity p, ServerLevel level, BlockPos feet) {
		if (Territory.isProtected(level, feet) || !level.getFluidState(feet).isEmpty()) return false;
		BlockPos headAbove = feet.above(2);
		if (!level.getBlockState(headAbove).isAir() && !Territory.isProtected(level, headAbove)
				&& (level.getBlockState(headAbove).is(BlockTags.LEAVES) || WorldScan.diggable(level.getBlockState(headAbove)))) {
			level.destroyBlock(headAbove, true, p);
		}
		if (!level.getBlockState(headAbove).isAir()) return false;
		BlockState block = takeScaffoldBlock(p);
		if (block == null) return false;
		p.snapTo(feet.getX() + 0.5, feet.getY() + 1.05, feet.getZ() + 0.5, p.getYRot(), p.getXRot());
		p.setDeltaMovement(0, 0.1, 0);
		level.setBlockAndUpdate(feet, block);
		p.swing(InteractionHand.MAIN_HAND);
		level.playSound(null, feet, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.7f, 1.0f);
		return true;
	}

	private boolean tryDigStepUp(PartnerBrain brain, ServerLevel level, BlockPos feet, Direction facing) {
		BlockPos ahead = feet.relative(facing);
		for (BlockPos clear : new BlockPos[]{ahead.above(2), ahead.above(), feet.above(2)}) {
			BlockState s = level.getBlockState(clear);
			if (!s.isAir() && !Territory.isProtected(level, clear) && (s.is(BlockTags.LEAVES) || WorldScan.diggable(s))) {
				level.destroyBlock(clear, true, brain.p);
				brain.p.swing(InteractionHand.MAIN_HAND);
				return true;
			}
		}
		return false;
	}

	private void placeScaffold(PartnerEntity p, ServerLevel level, BlockPos pos) {
		BlockState block = takeScaffoldBlock(p);
		if (block == null) return;
		level.setBlockAndUpdate(pos, block);
		p.swing(InteractionHand.MAIN_HAND);
		level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.7f, 1.0f);
	}

	private static BlockState takeScaffoldBlock(PartnerEntity p) {
		var bag = p.backpack();
		for (int i = 0; i < bag.getContainerSize(); i++) {
			ItemStack s = bag.getItem(i);
			if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
			if (s.is(Items.COBBLESTONE) || s.is(Items.DIRT) || s.is(Items.COBBLED_DEEPSLATE)
					|| s.is(Items.NETHERRACK) || s.is(ItemTags.PLANKS)) {
				s.shrink(1);
				bag.setChanged();
				return bi.getBlock().defaultBlockState();
			}
		}
		return Blocks.COBBLESTONE.defaultBlockState();
	}
}
