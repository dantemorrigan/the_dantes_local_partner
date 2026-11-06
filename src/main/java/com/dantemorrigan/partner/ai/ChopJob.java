package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Chops natural trees (logs under non-persistent leaves, so never a player's house) until it has gathered
 * {@code wanted} logs: walks to the trunk, cuts it from the bottom, steps into the stump to reach the top,
 * picks up the drops and replants a sapling.
 */
public final class ChopJob implements Job {
	private final int wanted;
	private int startLogs = -1, spent, scanCooldown, stuck, collectTicks, idle;
	private @Nullable BlockPos tree;
	private @Nullable BlockPos cutting;

	public ChopJob(int wanted) {
		this.wanted = wanted;
	}

	@Override
	public String toString() {
		return "Chop{tree=" + tree + " cutting=" + cutting + " start=" + startLogs + " stuck=" + stuck + " idle=" + idle + " collect=" + collectTicks + "}";
	}

	@Override
	public String activity() {
		return "wood";
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		if (startLogs < 0) startLogs = brain.logs();
		int gathered = brain.logs() + spent - startLogs;
		if (gathered >= wanted) {
			brain.say("done_wood", brain.logs() - startLogs);
			return Status.DONE;
		}
		// Like a player: the first logs become an axe.
		if (brain.logs() >= 2 && !Gear.has(p, Gear::isAxe) && !brain.breaker.active()) {
			int before = brain.logs();
			if (Crafting.craft(p, net.minecraft.world.item.Items.WOODEN_AXE)) {
				spent += before - brain.logs();
				brain.say("crafted", new ItemStack(net.minecraft.world.item.Items.WOODEN_AXE).getHoverName());
			}
		}
		if (!p.backpack().canAddItem(new ItemStack(net.minecraft.world.item.Items.OAK_LOG))) {
			brain.say("full");
			return Status.DONE;
		}
		// After a tree: pick up what fell before moving on.
		if (collectTicks > 0) {
			collectTicks--;
			if (tree != null && brain.collectDrops(level, Vec3.atCenterOf(tree), 7)) return Status.RUNNING;
			collectTicks = 0;
			replant(brain, level);
			tree = null;
			cutting = null;
		}
		if (tree == null) {
			if (scanCooldown-- > 0) return Status.RUNNING;
			scanCooldown = 40;
			tree = WorldScan.findTree(level, p.blockPosition(), 24, brain.badBlocks);
			if (tree == null) {
				if (++idle > 3) {
					brain.say("no_trees");
					return brain.logs() > startLogs ? Status.DONE : Status.FAILED;
				}
				return Status.RUNNING;
			}
			idle = 0;
			stuck = 0;
		}
		BlockPos next = nextLog(brain, level);
		if (next == null) {
			collectTicks = 60;
			return Status.RUNNING;
		}
		if (!BlockBreaker.inReach(p, next)) {
			// Step into the stump (already cut) to reach higher logs; otherwise walk to the trunk.
			BlockPos stand = level.getBlockState(tree).isAir() ? tree : null;
			if (stand != null && p.blockPosition().distSqr(stand) > 1) {
				p.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, PartnerEntity.WALK);
			} else if (stand == null) {
				brain.approach(next);
			}
			if (++stuck > 200) {
				brain.badBlocks.add(next);
				cutting = null;
				stuck = 0;
			}
			return Status.RUNNING;
		}
		p.getNavigation().stop();
		cutting = next;
		BlockBreaker.Result r = brain.breaker.tick(p, level, next);
		if (r == BlockBreaker.Result.FAILED) {
			brain.badBlocks.add(next);
			cutting = null;
			if (next.equals(tree)) tree = null;
		} else if (r == BlockBreaker.Result.DONE) {
			stuck = 0;
			cutting = null;
		}
		return Status.RUNNING;
	}

	/** Lowest remaining log of this tree that the partner can reach, or any log of the tree if none is in reach. */
	private @Nullable BlockPos nextLog(PartnerBrain brain, ServerLevel level) {
		if (tree == null) return null;
		if (cutting != null && level.getBlockState(cutting).is(BlockTags.LOGS)) return cutting;
		BlockPos best = null, reachable = null;
		for (int dy = 0; dy <= 12; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos pos = tree.offset(dx, dy, dz);
					if (brain.badBlocks.contains(pos) || !level.getBlockState(pos).is(BlockTags.LOGS)) continue;
					if (Math.abs(dx) + Math.abs(dz) > 0 && dy == 0 && !WorldScan.isNaturalLog(level, pos)) continue;
					if (best == null) best = pos;
					if (reachable == null && BlockBreaker.inReach(brain.p, pos)) reachable = pos;
				}
			}
		}
		return reachable != null ? reachable : best;
	}

	private void replant(PartnerBrain brain, ServerLevel level) {
		if (tree == null || !level.getBlockState(tree).isAir() || !level.getBlockState(tree.below()).is(BlockTags.DIRT)) return;
		var c = brain.p.backpack();
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.is(ItemTags.SAPLINGS) && s.getItem() instanceof BlockItem bi) {
				var state = bi.getBlock().defaultBlockState();
				if (!state.canSurvive(level, tree)) continue;
				level.setBlockAndUpdate(tree, state);
				s.shrink(1);
				c.setChanged();
				brain.p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
				return;
			}
		}
	}

	@Override
	public void stop(PartnerBrain brain, ServerLevel level) {
		brain.breaker.cancel(level, brain.p);
	}
}
