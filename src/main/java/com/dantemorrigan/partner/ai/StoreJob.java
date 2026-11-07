package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Finds a chest or barrel nearby (at the home base or near the owner), walks up to it, opens it, stores all
 * gathered resources (keeping the partner's own tools, armor and snacks), and reports what was stored.
 */
public final class StoreJob implements Job {
	private final boolean fallbackToOwner;
	private @Nullable BlockPos targetChest;
	private int ticks, stuck;

	public StoreJob(boolean fallbackToOwner) {
		this.fallbackToOwner = fallbackToOwner;
	}

	@Override
	public String activity() {
		return "storing";
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		ServerPlayer owner = p.getOwnerPlayer();
		if (++ticks > 20 * 25) return fallback(brain);
		if (!hasResourcesToStore(brain)) return Status.DONE;

		if (targetChest == null || getContainer(level, targetChest) == null) {
			targetChest = findChest(level, p.blockPosition(), 20);
			if (targetChest == null && owner != null && p.distanceTo(owner) < 32) {
				targetChest = findChest(level, owner.blockPosition(), 16);
			}
			stuck = 0;
			if (targetChest == null) {
				if (!fallbackToOwner) brain.say("no_chest");
				return fallback(brain);
			}
		}

		if (p.position().distanceToSqr(Vec3.atCenterOf(targetChest)) > 3.5 * 3.5 || !BlockBreaker.inReach(p, targetChest)) {
			brain.approach(targetChest);
			if (++stuck > 20 * 10) {
				brain.badBlocks.add(targetChest);
				targetChest = null;
			}
			return Status.RUNNING;
		}

		p.getNavigation().stop();
		p.getLookControl().setLookAt(Vec3.atCenterOf(targetChest));
		Container container = getContainer(level, targetChest);
		if (container == null) {
			targetChest = null;
			return Status.RUNNING;
		}

		Map<String, int[]> stored = depositInto(brain, container);
		if (stored.isEmpty()) {
			brain.badBlocks.add(targetChest);
			targetChest = null;
			return Status.RUNNING;
		}

		p.swing(InteractionHand.MAIN_HAND);
		boolean barrel = level.getBlockState(targetChest).is(Blocks.BARREL);
		level.playSound(null, targetChest, barrel ? SoundEvents.BARREL_OPEN : SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5f, 1.0f);
		brain.later(() -> level.playSound(null, targetChest, barrel ? SoundEvents.BARREL_CLOSE : SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 1.0f), 10);
		p.speak("stored_chest", PartnerEntity.summary(stored));
		if (p.order().oneShot()) p.setOrderQuiet(PartnerOrder.FOLLOW);
		return Status.DONE;
	}

	private Status fallback(PartnerBrain brain) {
		if (!fallbackToOwner) return Status.FAILED;
		return new GiveJob(true).tick(brain, (ServerLevel) brain.p.level());
	}

	public static boolean hasResourcesToStore(PartnerBrain brain) {
		if (!brain.ownerLoot.isEmpty()) return true;
		var bag = brain.p.backpack();
		for (int i = 0; i < bag.getContainerSize(); i++) {
			ItemStack s = bag.getItem(i);
			if (!s.isEmpty() && !brain.keepsForItself(s, i)) return true;
		}
		return false;
	}

	public static Map<String, int[]> depositInto(PartnerBrain brain, Container container) {
		Map<String, int[]> stored = new LinkedHashMap<>();
		for (int i = 0; i < brain.ownerLoot.getContainerSize(); i++) {
			ItemStack s = brain.ownerLoot.getItem(i);
			if (s.isEmpty()) continue;
			int moved = moveStack(s, container);
			if (moved > 0) stored.computeIfAbsent(s.getItem().getDescriptionId(), k -> new int[1])[0] += moved;
			if (s.isEmpty()) brain.ownerLoot.setItem(i, ItemStack.EMPTY);
		}
		var bag = brain.p.backpack();
		for (int i = 0; i < bag.getContainerSize(); i++) {
			ItemStack s = bag.getItem(i);
			if (s.isEmpty() || brain.keepsForItself(s, i)) continue;
			int moved = moveStack(s, container);
			if (moved > 0) stored.computeIfAbsent(s.getItem().getDescriptionId(), k -> new int[1])[0] += moved;
			if (s.isEmpty()) bag.setItem(i, ItemStack.EMPTY);
		}
		bag.setChanged();
		container.setChanged();
		return stored;
	}

	private static int moveStack(ItemStack stack, Container dest) {
		int start = stack.getCount();
		for (int i = 0; i < dest.getContainerSize() && !stack.isEmpty(); i++) {
			ItemStack slot = dest.getItem(i);
			if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
				int add = Math.min(stack.getCount(), slot.getMaxStackSize() - slot.getCount());
				slot.grow(add);
				stack.shrink(add);
			}
		}
		for (int i = 0; i < dest.getContainerSize() && !stack.isEmpty(); i++) {
			if (dest.getItem(i).isEmpty()) {
				dest.setItem(i, stack.copy());
				stack.setCount(0);
				break;
			}
		}
		return start - stack.getCount();
	}

	public static @Nullable Container getContainer(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chest) {
			return ChestBlock.getContainer(chest, state, level, pos, true);
		}
		if (state.is(Blocks.BARREL) && level.getBlockEntity(pos) instanceof Container c) {
			return c;
		}
		return null;
	}

	public static @Nullable BlockPos findChest(ServerLevel level, BlockPos center, int radius) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = -4; dy <= 5; dy++) {
					m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (!level.hasChunkAt(m)) continue;
					Container c = getContainer(level, m);
					if (c == null || !hasFreeSlot(c)) continue;
					double d = m.distSqr(center);
					if (d < bestD) {
						bestD = d;
						best = m.immutable();
					}
				}
			}
		}
		return best;
	}

	private static boolean hasFreeSlot(Container c) {
		for (int i = 0; i < c.getContainerSize(); i++) {
			if (c.getItem(i).isEmpty()) return true;
		}
		return false;
	}
}
