package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chops natural trees (logs under non-persistent leaves, never a player's build) like a player: walks up to the
 * trunk, cuts from the bottom with an axe if it has one (it makes one at a crafting table from the first logs),
 * steps into the stump for the upper logs, skips what it cannot reach within a couple of seconds, picks up the
 * drops on the way to the next tree and replants a sapling.
 *
 * <p>It decides by itself when it has enough: the wanted amount, no more trees around the spot where it started,
 * a full backpack or a few minutes of work. Then the brain sends it back to the owner with the haul.
 */
public final class ChopJob implements Job {
	private static final int AREA = 28, TIME_LIMIT = 20 * 60 * 4;
	private final int wanted;
	private int startLogs = -1, spent, subLogsBefore, ticks, stuck, noTree, collect, treeStartLogs;
	private @Nullable BlockPos anchor, base, cutting;
	private final List<BlockPos> treeLogs = new ArrayList<>();
	private @Nullable Job sub;

	public ChopJob(int wanted) {
		this.wanted = wanted;
	}

	@Override
	public int result(PartnerBrain brain) {
		return startLogs < 0 ? 0 : gathered(brain) * 10 + (sub != null ? 1 : 0);
	}

	@Override
	public String activity() {
		return sub != null ? sub.activity() : "wood";
	}

	@Override
	public String toString() {
		return "Chop{base=" + base + " cutting=" + cutting + " left=" + treeLogs.size() + " start=" + startLogs + " stuck=" + stuck + "}";
	}

	private int gathered(PartnerBrain brain) {
		return brain.logs() + spent - startLogs;
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		if (startLogs < 0) {
			startLogs = brain.logs();
			anchor = p.blockPosition();
		}
		ticks++;
		if (sub != null) {
			Status s = sub.tick(brain, level);
			if (s == Status.RUNNING) return s;
			sub.stop(brain, level);
			sub = null;
			spent += Math.max(0, subLogsBefore - brain.logs());
		}
		// Enough? (decided on its own)
		int got = gathered(brain);
		brain.detail = got + "/" + wanted;
		boolean full = !p.backpack().canAddItem(new ItemStack(Items.OAK_LOG, 16));
		if (got >= wanted || full || ticks > TIME_LIMIT && got > 0) {
			brain.say(full ? "full" : "done_wood", got);
			return Status.DONE;
		}
		// The first logs become an axe, made at a crafting table.
		if (brain.logs() >= 3 && base == null && !Gear.has(p, Gear::isAxe)) {
			for (Item axe : new Item[]{Items.STONE_AXE, Items.WOODEN_AXE}) {
				if (Crafting.canMake(p, axe)) {
					subLogsBefore = brain.logs();
					sub = new CraftJob(List.of(axe), true);
					return Status.RUNNING;
				}
			}
		}
		// Collect what fell from the last tree before heading on.
		if (collect > 0) {
			collect--;
			if (brain.collectDrops(level, p.position(), 6)) return Status.RUNNING;
			collect = 0;
		}
		if (base == null) {
			BlockPos search = anchor != null && p.blockPosition().distSqr(anchor) > 12 * 12 ? anchor : p.blockPosition();
			base = WorldScan.findTree(level, search, 20, brain.badBlocks);
			if (base != null && anchor != null && base.distSqr(anchor) > AREA * AREA) {
				brain.badBlocks.add(base);
				base = null;
			}
			if (base == null) {
				if (++noTree > 3) {
					brain.say(got > 0 ? "done_wood" : "no_trees", got);
					return got > 0 ? Status.DONE : Status.FAILED;
				}
				return Status.RUNNING;
			}
			noTree = 0;
			collectTree(level, base);
			stuck = 0;
			treeStartLogs = brain.logs();
			brain.report("tree_go", level.getBlockState(base).getBlock().getName(), (int) Math.sqrt(base.distSqr(p.blockPosition())));
		}
		treeLogs.removeIf(pos -> !level.getBlockState(pos).is(BlockTags.LOGS) || brain.badBlocks.contains(pos));
		if (treeLogs.isEmpty()) {
			brain.report("tree_done", Math.max(0, brain.logs() - treeStartLogs), got, wanted);
			replant(brain, level);
			base = null;
			cutting = null;
			collect = 50;
			return Status.RUNNING;
		}
		if (cutting == null || !level.getBlockState(cutting).is(BlockTags.LOGS)) cutting = pick(p);
		BlockPos target = cutting;
		if (!BlockBreaker.inReach(p, target)) {
			// Get in reach: step into the stump for the upper logs, otherwise walk up to the log.
			boolean stumpFree = !level.getBlockState(base).is(BlockTags.LOGS) && Parkour.passable(level, base) && Parkour.passable(level, base.above());
			if (stumpFree && !p.blockPosition().equals(base)) {
				p.getNavigation().moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, PartnerEntity.WALK);
			} else {
				brain.approach(target);
			}
			if (++stuck > 50) {
				// Out of reach from anywhere sensible: leave it (a player would not pillar up for one log).
				brain.badBlocks.add(target);
				cutting = null;
				stuck = 0;
			}
			return Status.RUNNING;
		}
		p.getNavigation().stop();
		BlockBreaker.Result r = brain.breaker.tick(p, level, target);
		if (r != BlockBreaker.Result.WORKING) {
			if (r == BlockBreaker.Result.FAILED) brain.badBlocks.add(target);
			cutting = null;
			stuck = 0;
		}
		return Status.RUNNING;
	}

	/** All logs of this tree: connected to the trunk, within a few blocks of it. */
	private void collectTree(ServerLevel level, BlockPos start) {
		treeLogs.clear();
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		open.add(start);
		seen.add(start);
		while (!open.isEmpty() && treeLogs.size() < 80) {
			BlockPos pos = open.poll();
			treeLogs.add(pos);
			for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				BlockPos n = pos.offset(dx, dy, dz);
				if (seen.contains(n) || Math.abs(n.getX() - start.getX()) > 4 || Math.abs(n.getZ() - start.getZ()) > 4) continue;
				seen.add(n);
				if (level.getBlockState(n).is(BlockTags.LOGS)) open.add(n);
			}
		}
	}

	/** The lowest log in reach, else the lowest one overall. */
	private BlockPos pick(PartnerEntity p) {
		BlockPos best = null;
		boolean bestReach = false;
		for (BlockPos pos : treeLogs) {
			boolean reach = BlockBreaker.inReach(p, pos);
			if (best == null || reach && !bestReach || reach == bestReach && pos.getY() < best.getY()) {
				best = pos;
				bestReach = reach;
			}
		}
		return best;
	}

	private void replant(PartnerBrain brain, ServerLevel level) {
		if (base == null || !level.getBlockState(base).isAir() || !level.getBlockState(base.below()).is(BlockTags.DIRT)) return;
		var c = brain.p.backpack();
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.is(ItemTags.SAPLINGS) && s.getItem() instanceof BlockItem bi) {
				var state = bi.getBlock().defaultBlockState();
				if (!state.canSurvive(level, base)) continue;
				level.setBlockAndUpdate(base, state);
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
		if (sub != null) sub.stop(brain, level);
	}
}
