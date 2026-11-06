package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Mining. First ores a player could see from here (cave walls, cliffs); when there are none, a safe 1-wide
 * staircase down to the depth where that ore lives, then a branch tunnel, mining every ore it uncovers.
 * Never digs next to lava or water, never through blocks a player placed (only natural ground), lights the
 * tunnel with torches when it has some. Makes itself a pickaxe first when it has none.
 */
public final class MineJob implements Job {
	private static final int TIME_LIMIT = 20 * 60 * 6;

	private final WorldScan.OreKind kind;
	private final int wanted;
	private final Predicate<ItemStack> product;
	private int start = -1, ticks, scanCooldown, stuck, collectTicks;
	private @Nullable BlockPos ore;
	private @Nullable Job prerequisite;
	// staircase state
	private @Nullable Direction dir;
	private int turns, tunnel, sinceTorch, moveTicks;
	private @Nullable BlockPos stepTarget;

	public MineJob(WorldScan.OreKind kind, int wanted) {
		this.kind = kind;
		this.wanted = wanted;
		this.product = switch (kind) {
			case COAL -> s -> s.is(Items.COAL);
			case IRON -> s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_INGOT);
			case DIAMOND -> s -> s.is(Items.DIAMOND);
			case STONE -> s -> s.is(ItemTags.STONE_TOOL_MATERIALS);
			case ANY -> MineJob::isOreProduct;
		};
	}

	private static boolean isOreProduct(ItemStack s) {
		String path = Gear.id(s).getPath();
		return path.startsWith("raw_") || s.is(Items.COAL) || s.is(Items.DIAMOND) || s.is(Items.EMERALD)
				|| s.is(Items.REDSTONE) || s.is(Items.LAPIS_LAZULI) || s.is(Items.QUARTZ) || s.is(Items.ANCIENT_DEBRIS)
				|| s.is(Items.GOLD_NUGGET);
	}

	private int requiredTier() {
		return switch (kind) {
			case IRON -> 2;
			case DIAMOND -> 3;
			default -> 1;
		};
	}

	private int targetY(ServerLevel level, PartnerEntity p) {
		return switch (kind) {
			case DIAMOND -> Math.max(level.getMinY() + 6, -54);
			case IRON, ANY -> 16;
			case COAL -> Math.min(p.getBlockY() - 4, 48);
			case STONE -> p.getBlockY() - 3;
		};
	}

	@Override
	public String activity() {
		if (prerequisite != null) return prerequisite.activity();
		return dir != null && ore == null ? "stairs" : "ore";
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		if (start < 0) start = Gear.count(p.backpack(), product);
		int got = Gear.count(p.backpack(), product) - start;
		if (got >= wanted) {
			if (kind != WorldScan.OreKind.STONE) brain.say("done_ore", got);
			return Status.DONE;
		}
		if (++ticks > TIME_LIMIT) {
			brain.say(got > 0 ? "done_ore" : "no_ore", got);
			return got > 0 ? Status.DONE : Status.FAILED;
		}
		if (!p.backpack().canAddItem(new ItemStack(Items.COBBLESTONE)) && !p.backpack().canAddItem(new ItemStack(Items.RAW_IRON))) {
			brain.say("full");
			return Status.DONE;
		}
		// Tools first.
		if (prerequisite != null) {
			Status s = prerequisite.tick(brain, level);
			if (s == Status.RUNNING) return s;
			prerequisite.stop(brain, level);
			prerequisite = null;
			if (s == Status.FAILED) return Status.FAILED;
		}
		if (!ensurePickaxe(brain)) return prerequisite != null ? Status.RUNNING : Status.FAILED;
		torches(p);

		if (collectTicks > 0) {
			collectTicks--;
			if (ore != null && brain.collectDrops(level, Vec3.atCenterOf(ore), 5)) return Status.RUNNING;
			collectTicks = 0;
			ore = null;
		}
		if (ore == null && scanCooldown-- <= 0) {
			scanCooldown = dir == null ? 30 : 0;
			int radius = dir == null ? 12 : 4;
			ore = WorldScan.findExposed(level, p.blockPosition(), radius, dir == null ? 8 : 4, s -> mineable(brain, s), brain.badBlocks);
			stuck = 0;
		}
		if (ore != null) return mineOre(brain, level);
		return staircase(brain, level);
	}

	private boolean mineable(PartnerBrain brain, BlockState s) {
		boolean wanted = WorldScan.isOre(s, kind)
				|| (kind == WorldScan.OreKind.IRON || kind == WorldScan.OreKind.DIAMOND) && WorldScan.isOre(s, WorldScan.OreKind.COAL);
		if (!wanted) return false;
		return !s.requiresCorrectToolForDrops() || bestPick(brain.p).isCorrectToolForDrops(s);
	}

	private Status mineOre(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		BlockPos target = ore;
		if (target == null) return Status.RUNNING;
		if (level.getBlockState(target).isAir()) {
			collectTicks = 40;
			return Status.RUNNING;
		}
		if (WorldScan.dangerousFluidNear(level, target)) {
			brain.badBlocks.add(target);
			ore = null;
			return Status.RUNNING;
		}
		if (!brain.approach(target)) {
			if (++stuck > 160) {
				brain.badBlocks.add(target);
				ore = null;
			}
			return Status.RUNNING;
		}
		BlockBreaker.Result r = brain.breaker.tick(p, level, target);
		if (r == BlockBreaker.Result.DONE) collectTicks = 40;
		else if (r == BlockBreaker.Result.FAILED) {
			brain.badBlocks.add(target);
			ore = null;
		}
		return Status.RUNNING;
	}

	/** Digs a staircase down (then a straight tunnel at the target depth), one step at a time. */
	private Status staircase(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		if (dir == null) {
			dir = p.getDirection();
			stepTarget = null;
		}
		BlockPos cur = p.blockPosition();
		if (stepTarget != null) {
			// Walking into the freshly dug step.
			if (cur.equals(stepTarget) || p.position().distanceToSqr(Vec3.atBottomCenterOf(stepTarget)) < 0.2) {
				stepTarget = null;
				moveTicks = 0;
				scanCooldown = 0;
				if (++sinceTorch >= 8) placeTorch(brain, level, cur.relative(dir.getOpposite()));
				if (cur.getY() <= targetY(level, p) && ++tunnel % 20 == 0) dir = dir.getClockWise();
				return Status.RUNNING;
			}
			p.getMoveControl().setWantedPosition(stepTarget.getX() + 0.5, stepTarget.getY(), stepTarget.getZ() + 0.5, PartnerEntity.WALK);
			if (++moveTicks > 60) {
				p.snapTo(stepTarget.getX() + 0.5, stepTarget.getY(), stepTarget.getZ() + 0.5, p.getYRot(), p.getXRot());
				moveTicks = 0;
			}
			return Status.RUNNING;
		}
		boolean down = cur.getY() > targetY(level, p);
		BlockPos ahead = cur.relative(dir);
		BlockPos next = down ? ahead.below() : ahead;
		BlockPos[] clear = down ? new BlockPos[]{ahead.above(), ahead, ahead.below()} : new BlockPos[]{ahead.above(), ahead};
		BlockState floor = level.getBlockState(next.below());
		boolean safe = floor.isFaceSturdy(level, next.below(), Direction.UP) && floor.getFluidState().isEmpty()
				&& !floor.is(Blocks.MAGMA_BLOCK);
		for (BlockPos c : clear) {
			BlockState s = level.getBlockState(c);
			if (!WorldScan.diggable(s) || WorldScan.dangerousFluidNear(level, c) || brain.badBlocks.contains(c)) safe = false;
		}
		if (!safe) {
			dir = dir.getClockWise();
			if (++turns > 4) {
				brain.say("no_ore");
				return Gear.count(p.backpack(), product) > start ? Status.DONE : Status.FAILED;
			}
			return Status.RUNNING;
		}
		for (BlockPos c : clear) {
			if (level.getBlockState(c).isAir()) continue;
			BlockBreaker.Result r = brain.breaker.tick(p, level, c);
			if (r == BlockBreaker.Result.FAILED) brain.badBlocks.add(c.immutable());
			return Status.RUNNING;
		}
		turns = 0;
		stepTarget = next.immutable();
		return Status.RUNNING;
	}

	private boolean ensurePickaxe(PartnerBrain brain) {
		int tier = brain.pickTier();
		int need = requiredTier();
		if (tier >= need) return true;
		PartnerEntity p = brain.p;
		// Make the pickaxe at a real crafting table (CraftJob finds or places one).
		Item[] picks = need >= 3 ? new Item[]{Items.IRON_PICKAXE} : new Item[]{Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE};
		for (Item pick : picks) {
			if (Gear.pickTier(new ItemStack(pick)) > tier && Crafting.canMake(p, pick)) {
				prerequisite = new CraftJob(java.util.List.of(pick), true);
				return false;
			}
		}
		if (need >= 3) {
			brain.say("need_iron_pick");
			return false;
		}
		if (tier == 0) {
			prerequisite = new ChopJob(3);
			brain.say("need_wood_for_pick");
			return false;
		}
		prerequisite = new MineJob(WorldScan.OreKind.STONE, 3);
		return false;
	}

	private static ItemStack bestPick(PartnerEntity p) {
		ItemStack best = Gear.isPickaxe(p.getMainHandItem()) ? p.getMainHandItem() : ItemStack.EMPTY;
		for (int i = 0; i < p.backpack().getContainerSize(); i++) {
			ItemStack s = p.backpack().getItem(i);
			if (Gear.pickTier(s) > Gear.pickTier(best)) best = s;
		}
		return best;
	}

	private static void torches(PartnerEntity p) {
		if (p.tickCount % 100 == 0 && Gear.count(p.backpack(), Items.TORCH) < 4 && Gear.count(p.backpack(), s -> s.is(ItemTags.COALS)) > 2) {
			Crafting.craft(p, Items.TORCH);
		}
	}

	private void placeTorch(PartnerBrain brain, ServerLevel level, BlockPos at) {
		if (level.getMaxLocalRawBrightness(at) >= 8 || !level.getBlockState(at).isAir()) return;
		if (!Blocks.TORCH.defaultBlockState().canSurvive(level, at)) return;
		if (Gear.take(brain.p.backpack(), s -> s.is(Items.TORCH), 1) == 0) return;
		level.setBlockAndUpdate(at, Blocks.TORCH.defaultBlockState());
		sinceTorch = 0;
	}

	@Override
	public void stop(PartnerBrain brain, ServerLevel level) {
		brain.breaker.cancel(level, brain.p);
		if (prerequisite != null) prerequisite.stop(brain, level);
	}
}
