package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Smelting at a real furnace in the world: uses a furnace standing nearby or places its own from the backpack,
 * lights it up with fire and smoke, tends the fire until ores/food are smelted, then packs its own furnace back
 * into the backpack so nothing is left behind.
 */
public final class SmeltJob implements Job {
	private enum Stage { FIND, WALK, COOK, PACK, COLLECT }

	private Stage stage = Stage.FIND;
	private @Nullable BlockPos furnace;
	private boolean ownFurnace;
	private int ticks, workTicks, smelted;

	@Override
	public int result(PartnerBrain brain) {
		return smelted * 10 + stage.ordinal();
	}

	@Override
	public int stallTicks() {
		return 20 * 40;
	}

	@Override
	public String activity() {
		return "smelt";
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		if (++ticks > 20 * 120) return smelted > 0 ? Status.DONE : Status.FAILED;
		switch (stage) {
			case FIND -> {
				if (!Smelter.hasSmeltable(p.backpack(), level)) return Status.DONE;
				if (!brain.smelter.ensureBurning(p.backpack(), level)) return Status.FAILED;
				furnace = findFurnace(p, 12);
				if (furnace != null) {
					ownFurnace = false;
					stage = Stage.WALK;
					return Status.RUNNING;
				}
				if (Gear.count(p.backpack(), Items.FURNACE) == 0) return Status.FAILED;
				BlockPos spot = placeSpot(level, p);
				if (spot == null) return Status.FAILED;
				Gear.take(p.backpack(), s -> s.is(Items.FURNACE), 1);
				Direction facing = Direction.fromYRot(p.getYRot()).getOpposite();
				BlockState state = Blocks.FURNACE.defaultBlockState()
						.setValue(AbstractFurnaceBlock.FACING, facing)
						.setValue(AbstractFurnaceBlock.LIT, true);
				p.getLookControl().setLookAt(Vec3.atCenterOf(spot));
				p.swing(InteractionHand.MAIN_HAND);
				level.setBlockAndUpdate(spot, state);
				level.playSound(null, spot, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1f, 0.8f);
				brain.report("furnace_placed");
				furnace = spot;
				ownFurnace = true;
				stage = Stage.WALK;
			}
			case WALK -> {
				if (furnace == null || !isFurnace(level.getBlockState(furnace))) {
					stage = Stage.FIND;
					return Status.RUNNING;
				}
				if (p.position().distanceToSqr(Vec3.atBottomCenterOf(furnace)) < 3.2 * 3.2 && BlockBreaker.inReach(p, furnace)) {
					p.getNavigation().stop();
					stage = Stage.COOK;
					workTicks = 0;
				} else brain.approach(furnace);
			}
			case COOK -> {
				if (furnace == null || !isFurnace(level.getBlockState(furnace))) {
					stage = Stage.FIND;
					return Status.RUNNING;
				}
				int left = Smelter.countSmeltable(p.backpack(), level);
				if (left <= 0 || !brain.smelter.ensureBurning(p.backpack(), level)) {
					brain.onPickedUp();
					stage = ownFurnace ? Stage.PACK : Stage.COLLECT;
					workTicks = 0;
					return Status.RUNNING;
				}
				p.getLookControl().setLookAt(Vec3.atCenterOf(furnace));
				brain.detail = smelted + "/" + (smelted + left);
				workTicks++;
				if (workTicks % 15 == 0) {
					p.swing(InteractionHand.MAIN_HAND);
					level.sendParticles(ParticleTypes.FLAME, furnace.getX() + 0.5, furnace.getY() + 0.4, furnace.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0.01);
					level.sendParticles(ParticleTypes.SMOKE, furnace.getX() + 0.5, furnace.getY() + 1.0, furnace.getZ() + 0.5, 2, 0.1, 0.1, 0.1, 0.01);
					level.playSound(null, furnace, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.7f, 1.0f);
				}
				if (workTicks % 60 == 0) {
					if (brain.smelter.finishOne(p, level)) smelted++;
				}
			}
			case PACK -> {
				if (furnace == null || !level.getBlockState(furnace).is(Blocks.FURNACE)) {
					stage = Stage.COLLECT;
					workTicks = 0;
					return Status.RUNNING;
				}
				if (!BlockBreaker.inReach(p, furnace)) {
					brain.approach(furnace);
					return Status.RUNNING;
				}
				BlockBreaker.Result r = brain.breaker.tick(p, level, furnace, true);
				if (r != BlockBreaker.Result.WORKING) {
					if (level.getBlockState(furnace).is(Blocks.FURNACE)) {
						// Fallback if it had no pickaxe to break the furnace cleanly: pick it straight into the backpack.
						level.removeBlock(furnace, false);
						p.backpack().addItem(new ItemStack(Items.FURNACE));
					}
					stage = Stage.COLLECT;
					workTicks = 0;
				}
			}
			case COLLECT -> {
				if (workTicks == 0 && ownFurnace) brain.report("furnace_packed");
				if (furnace != null && ownFurnace && ++workTicks < 40 && brain.collectDrops(level, Vec3.atCenterOf(furnace), 4)) return Status.RUNNING;
				return smelted > 0 ? Status.DONE : Status.FAILED;
			}
		}
		return Status.RUNNING;
	}

	private static boolean isFurnace(BlockState s) {
		return s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.SMOKER);
	}

	public static @Nullable BlockPos findFurnace(PartnerEntity p, int radius) {
		var level = p.level();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos center = p.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, 3, radius))) {
			if (!isFurnace(level.getBlockState(pos))) continue;
			double d = p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
			if (d < bestD) {
				bestD = d;
				best = pos.immutable();
			}
		}
		return best;
	}

	private static @Nullable BlockPos placeSpot(ServerLevel level, PartnerEntity p) {
		BlockPos feet = p.blockPosition();
		for (int dy : new int[]{0, -1, 1}) {
			for (Direction d : Direction.Plane.HORIZONTAL) {
				for (int dist = 1; dist <= 2; dist++) {
					BlockPos pos = feet.relative(d, dist).above(dy);
					if (!level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty()) continue;
					if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue;
					if (!level.getEntities(null, new net.minecraft.world.phys.AABB(pos)).isEmpty()) continue;
					if (!level.mayInteract(p, pos)) continue;
					return pos;
				}
			}
		}
		return null;
	}

	@Override
	public void stop(PartnerBrain brain, ServerLevel level) {
		brain.breaker.cancel(level, brain.p);
		if (ownFurnace && furnace != null && level.getBlockState(furnace).is(Blocks.FURNACE) && stage != Stage.COLLECT) {
			level.removeBlock(furnace, false);
			brain.p.backpack().addItem(new ItemStack(Items.FURNACE));
		}
	}
}
