package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Crafting at a real crafting table: uses one standing nearby (the owner's, for example) or takes its own out of
 * the backpack (making one from planks if needed) and places it next to itself. Walks up to it, works at it one
 * item at a time (arm swings and wood sounds, about half a second per item), then breaks its own table again
 * with the right tool and picks it up, so it does not litter the world.
 */
public final class CraftJob implements Job {
	private enum Stage { FIND, WALK, WORK, PACK, COLLECT }

	private final ArrayDeque<Item> queue;
	private final boolean announce;
	private Stage stage = Stage.FIND;
	private @Nullable BlockPos table;
	private boolean ownTable;
	private int ticks, workTicks, crafted;

	public CraftJob(List<Item> items, boolean announce) {
		this.queue = new ArrayDeque<>(items);
		this.announce = announce;
	}

	@Override
	public int result(PartnerBrain brain) {
		return crafted * 10 + stage.ordinal();
	}

	@Override
	public int stallTicks() {
		return 20 * 30;
	}

	@Override
	public String activity() {
		return "craft";
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		if (++ticks > 20 * 45) return crafted > 0 ? Status.DONE : Status.FAILED;
		switch (stage) {
			case FIND -> {
				if (queue.isEmpty()) return Status.DONE;
				table = Crafting.findTable(p, 12);
				if (table != null) {
					ownTable = false;
					stage = Stage.WALK;
					return Status.RUNNING;
				}
				if (Gear.count(p.backpack(), Items.CRAFTING_TABLE) == 0 && !Crafting.craft(p, Items.CRAFTING_TABLE)) return Status.FAILED;
				BlockPos spot = placeSpot(level, p);
				if (spot == null) return Status.FAILED;
				Gear.take(p.backpack(), s -> s.is(Items.CRAFTING_TABLE), 1);
				p.getLookControl().setLookAt(Vec3.atCenterOf(spot));
				p.swing(InteractionHand.MAIN_HAND);
				level.setBlockAndUpdate(spot, Blocks.CRAFTING_TABLE.defaultBlockState());
				level.playSound(null, spot, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1f, 0.8f);
				brain.report("table_placed");
				table = spot;
				ownTable = true;
				stage = Stage.WALK;
			}
			case WALK -> {
				if (table == null || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
					stage = Stage.FIND;
					return Status.RUNNING;
				}
				if (p.position().distanceToSqr(Vec3.atBottomCenterOf(table)) < 3.2 * 3.2 && BlockBreaker.inReach(p, table)) {
					p.getNavigation().stop();
					stage = Stage.WORK;
					workTicks = 0;
				} else brain.approach(table);
			}
			case WORK -> {
				if (table == null || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
					stage = Stage.FIND;
					return Status.RUNNING;
				}
				p.getLookControl().setLookAt(Vec3.atCenterOf(table));
				workTicks++;
				if (workTicks % 4 == 0) {
					p.swing(InteractionHand.MAIN_HAND);
					level.playSound(null, table, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 0.5f, 1.2f + p.getRandom().nextFloat() * 0.2f);
				}
				if (workTicks % 12 != 0) return Status.RUNNING;
				brain.detail = crafted + "/" + (crafted + queue.size());
				Item next = queue.poll();
				if (next != null) {
					if (Crafting.craft(p, next)) {
						crafted++;
						if (announce) brain.say("crafted", new ItemStack(next).getHoverName());
					}
					return Status.RUNNING;
				}
				brain.onPickedUp(); // re-equip with what was made
				stage = ownTable ? Stage.PACK : Stage.COLLECT;
			}
			case PACK -> {
				if (table == null || !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
					stage = Stage.COLLECT;
					workTicks = 0;
					return Status.RUNNING;
				}
				if (!BlockBreaker.inReach(p, table)) {
					brain.approach(table);
					return Status.RUNNING;
				}
				BlockBreaker.Result r = brain.breaker.tick(p, level, table);
				if (r != BlockBreaker.Result.WORKING) {
					stage = Stage.COLLECT;
					workTicks = 0;
				}
			}
			case COLLECT -> {
				if (workTicks == 0 && ownTable) brain.report("table_packed");
				if (table != null && ownTable && ++workTicks < 40 && brain.collectDrops(level, Vec3.atCenterOf(table), 4)) return Status.RUNNING;
				return crafted > 0 ? Status.DONE : Status.FAILED;
			}
		}
		return Status.RUNNING;
	}

	/** A free spot on the ground right next to the partner (not where it or anyone stands). */
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
		// Interrupted while its own table still stands: take it back without breaking animation.
		if (ownTable && table != null && level.getBlockState(table).is(Blocks.CRAFTING_TABLE) && stage != Stage.COLLECT) {
			level.removeBlock(table, false);
			brain.p.backpack().addItem(new ItemStack(Items.CRAFTING_TABLE));
		}
	}
}
