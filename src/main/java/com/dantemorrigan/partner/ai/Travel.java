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

/**
 * Long-distance walking and self-rescue: the pathfinder only plans a few dozen blocks, so the partner walks toward
 * far goals through surface waypoints ~12 blocks apart, jumps gaps on the way, veers around obstacles, and when
 * genuinely trapped (in a hole, behind foliage/cliff, across water) digs its way out, pillars up or bridges across.
 */
public final class Travel {
	private Vec3 lastPos = Vec3.ZERO;
	private int stuck, veer;
	private float veerAngle;

	/** One tick of travel toward {@code goal}. Returns false when it had to call SOS / evacuate after all self-rescue tries. */
	public boolean tick(PartnerBrain brain, ServerLevel level, Vec3 goal, double speed) {
		PartnerEntity p = brain.p;
		Vec3 to = goal.subtract(p.position()).multiply(1, 0, 1);
		double dist = to.length();
		if (dist < 1) return true;
		Vec3 dir = to.normalize();
		if (veerAngle != 0) dir = dir.yRot(veerAngle);
		Vec3 wp = dist < 14 ? goal : p.position().add(dir.scale(12));
		if (dist >= 14) {
			BlockPos top = findOpenSurface(level, BlockPos.containing(wp));
			wp = Vec3.atBottomCenterOf(top);
		}
		if (p.tickCount % 15 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(wp.x, wp.y, wp.z, speed);
		brain.jumpIfBlocked(wp);

		// Active self-rescue while stuck: clear leaves/blocks ahead, bridge across water/drops, or pillar out of a pit.
		if (stuck >= 1 && p.tickCount % 8 == 0) {
			selfRescue(brain, level, dir, wp);
		}

		if (p.tickCount % 20 == 0) {
			if (p.position().distanceToSqr(lastPos) < 1.2) {
				// Blocked: try going around (alternate sides, wider each time) and trigger self-rescue.
				stuck++;
				veerAngle = (float) Math.toRadians((stuck % 2 == 0 ? 1 : -1) * Math.min(95, 35 * ((stuck + 1) / 2)));
				veer = 5;
				if (stuck == 4) brain.report("self_unstuck");
			} else if (veer > 0 && --veer == 0) {
				veerAngle = 0;
			}
			if (p.position().distanceToSqr(lastPos) >= 1.2) stuck = Math.max(0, stuck - 1);
			lastPos = p.position();
		}
		if (stuck >= 8) {
			stuck = 0;
			brain.emergencyUnstuck(level);
			return false;
		}
		return true;
	}

	private static BlockPos findOpenSurface(ServerLevel level, BlockPos approx) {
		BlockPos best = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, approx);
		for (int r = 0; r <= 3; r++) {
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

	/** Uses player-like maneuvers to get out of a trap: break blocking leaves/stone, bridge a gap, or pillar out of a hole. */
	private void selfRescue(PartnerBrain brain, ServerLevel level, Vec3 dir, Vec3 wp) {
		PartnerEntity p = brain.p;
		Direction facing = Direction.getApproximateNearest(dir.x, 0, dir.z);
		BlockPos feet = p.blockPosition();
		BlockPos ahead = feet.relative(facing);

		// 1. Trapped in a pit or deep below the target waypoint: pillar straight up or dig steps upward.
		int surfaceY = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet).getY();
		if (wp.y - p.getY() >= 2.5 || surfaceY - feet.getY() >= 3) {
			if (tryPillarUp(p, level, feet)) return;
			if (tryDigStepUp(brain, level, feet, facing)) return;
		}

		// 2. Blocked by leaves, cobwebs, dirt or stone right in front: break through (never inside player builds).
		for (BlockPos check : new BlockPos[]{ahead.above(), ahead, feet.above(2)}) {
			BlockState s = level.getBlockState(check);
			if (!s.isAir() && !Territory.isProtected(level, check)
					&& (s.is(BlockTags.LEAVES) || s.is(Blocks.COBWEB) || WorldScan.diggable(s))) {
				brain.breaker.tick(p, level, check);
				return;
			}
		}

		// 3. Water or a deep chasm right ahead: place a stepping block to bridge across.
		BlockPos bridgePos = ahead.below();
		BlockState belowAhead = level.getBlockState(bridgePos);
		if ((belowAhead.isAir() || !belowAhead.getFluidState().isEmpty()) && level.getBlockState(ahead).isAir()
				&& !Territory.isProtected(level, bridgePos)) {
			placeScaffold(p, level, bridgePos);
		}
	}

	private boolean tryPillarUp(PartnerEntity p, ServerLevel level, BlockPos feet) {
		if (Territory.isProtected(level, feet)) return false;
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
		return Blocks.DIRT.defaultBlockState();
	}
}
