package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * "Do your thing": the partner plays survival on its own, one sensible step at a time, always near the owner.
 * Food → wood → wooden tools → stone tools and a furnace → iron (mined, smelted on the go) → iron tools and armor
 * → diamonds → diamond gear. Anything it can craft from what it carries gets crafted right away. A step that
 * fails (no trees, no ore around) is put aside for two minutes; when there is nothing to do it guards the owner.
 */
public final class AutoJob implements Job {
	private static final int RETRY = 20 * 120;
	private @Nullable Job step;
	private String stepKey = "free";
	private int idle;

	@Override
	public String activity() {
		return step != null ? step.activity() : "free";
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		if (step == null) {
			craftUpgrades(brain);
			if (!choose(brain)) {
				idle = 20 * 30; // nothing to do: stay with the owner for a while
				stepKey = "idle";
			}
		}
		if (step == null) {
			ServerPlayer owner = brain.p.getOwnerPlayer();
			if (owner != null) brain.follow(owner, 2.5, 5.0, "free");
			return --idle > 0 ? Status.RUNNING : Status.DONE;
		}
		Status s = step.tick(brain, level);
		if (s == Status.RUNNING) return s;
		step.stop(brain, level);
		if (s == Status.FAILED) brain.autoBlocked.put(stepKey, brain.p.tickCount + RETRY);
		step = null;
		craftUpgrades(brain);
		return Status.DONE;
	}

	private boolean choose(PartnerBrain brain) {
		PartnerEntity p = brain.p;
		var c = p.backpack();
		int tier = brain.pickTier();
		int wood = brain.logs() * 4 + Gear.count(c, s -> s.is(ItemTags.PLANKS));
		int cobble = Gear.count(c, s -> s.is(ItemTags.STONE_TOOL_MATERIALS));
		int iron = Gear.count(c, Items.IRON_INGOT) + Gear.count(c, Items.RAW_IRON);
		boolean furnace = Gear.count(c, Items.FURNACE) > 0;
		boolean fuel = Gear.count(c, s -> s.is(ItemTags.COALS)) >= 2 || Gear.count(c, s -> s.is(ItemTags.PLANKS)) > 12;

		if (Gear.foodPoints(c) < 12 && start(brain, "hunt", new HuntJob(30))) return true;
		if (wood < 16 && start(brain, "wood", new ChopJob(tier == 0 ? 6 : 8))) return true;
		if ((tier < 2 || !furnace) && cobble < 11 && start(brain, "stone", new MineJob(WorldScan.OreKind.STONE, 11 - cobble))) return true;
		if (tier >= 2 && !fuel && furnace && start(brain, "coal", new MineJob(WorldScan.OreKind.COAL, 8))) return true;
		int ironWanted = ironWanted(p);
		if (tier >= 2 && iron < ironWanted && start(brain, "iron", new MineJob(WorldScan.OreKind.IRON, Math.min(12, ironWanted - iron)))) return true;
		if (Gear.count(c, Items.RAW_IRON) > 0 && furnace) return false; // smelting: wait with the owner
		int diamondsWanted = diamondsWanted(p);
		if (tier >= 3 && Gear.count(c, Items.DIAMOND) < diamondsWanted
				&& start(brain, "diamond", new MineJob(WorldScan.OreKind.DIAMOND, Math.min(5, diamondsWanted - Gear.count(c, Items.DIAMOND))))) return true;
		return false;
	}

	/** True when the plan has nothing left to work on right now (same checks as {@link #choose}, without starting). */
	static boolean nothingToDo(PartnerBrain brain) {
		AutoJob probe = new AutoJob();
		probe.dryRun = true;
		return !probe.choose(brain);
	}

	private boolean dryRun;

	private boolean start(PartnerBrain brain, String key, Job job) {
		Integer until = brain.autoBlocked.get(key);
		if (until != null && until > brain.p.tickCount) return false;
		if (dryRun) return true;
		step = job;
		stepKey = key;
		return true;
	}

	/** Iron still needed for: pickaxe, sword, the four armor pieces. */
	private static int ironWanted(PartnerEntity p) {
		int n = 0;
		if (Gear.pickTierOf(p) < 3) n += 3;
		if (!hasAtLeast(p, Items.IRON_SWORD)) n += 2;
		n += missingArmor(p, 3);
		return n;
	}

	private static int diamondsWanted(PartnerEntity p) {
		int n = 0;
		if (Gear.pickTierOf(p) < 4) n += 3;
		if (!hasAtLeast(p, Items.DIAMOND_SWORD)) n += 2;
		n += missingArmor(p, 4);
		return n;
	}

	/** Material needed for armor pieces below the given tier (3 iron, 4 diamond). */
	private static int missingArmor(PartnerEntity p, int tier) {
		int[] cost = {5, 8, 7, 4};
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		double[] ironValue = {4, 12, 10, 4}; // armor*2 of iron pieces
		int n = 0;
		for (int i = 0; i < 4; i++) {
			double v = Gear.armorValue(p.getItemBySlot(slots[i]));
			double needed = tier == 3 ? ironValue[i] : ironValue[i] + 1;
			if (v < needed) n += cost[i];
		}
		return n;
	}

	private static boolean hasAtLeast(PartnerEntity p, Item sword) {
		double want = Gear.attackDamage(new ItemStack(sword));
		return Gear.has(p, s -> Gear.isSword(s) && Gear.attackDamage(s) >= want);
	}

	/** Crafts every upgrade the backpack allows right now, best first. */
	static void craftUpgrades(PartnerBrain brain) {
		PartnerEntity p = brain.p;
		var c = p.backpack();
		int tier = Gear.pickTierOf(p);
		if (tier < 4 && Gear.count(c, Items.DIAMOND) >= 3) announce(brain, Items.DIAMOND_PICKAXE);
		if (tier < 3 && Gear.count(c, Items.IRON_INGOT) >= 3) announce(brain, Items.IRON_PICKAXE);
		if (tier < 2 && Gear.count(c, s -> s.is(ItemTags.STONE_TOOL_MATERIALS)) >= 3) announce(brain, Items.STONE_PICKAXE);
		if (Gear.pickTierOf(p) < 1) announce(brain, Items.WOODEN_PICKAXE);
		for (Item sword : new Item[]{Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD}) {
			if (hasAtLeast(p, sword)) break;
			if (announce(brain, sword)) break;
		}
		if (Gear.count(c, Items.FURNACE) == 0 && Gear.count(c, s -> s.is(ItemTags.STONE_TOOL_MATERIALS)) >= 8) announce(brain, Items.FURNACE);
		armor(brain, Items.DIAMOND, new Item[]{Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_HELMET, Items.DIAMOND_BOOTS});
		armor(brain, Items.IRON_INGOT, new Item[]{Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_HELMET, Items.IRON_BOOTS});
		// Shield once the iron armor is covered; bow and arrows from what the hunts and fights drop.
		if (!Gear.has(p, Gear::isShield) && Gear.count(c, Items.IRON_INGOT) > ironWanted(p)) announce(brain, Items.SHIELD);
		if (!Gear.has(p, Gear::isBow) && Gear.count(c, Items.STRING) >= 3) announce(brain, Items.BOW);
		for (int i = 0; i < 4 && Gear.arrows(p) < 32 && Gear.has(p, Gear::isBow); i++) {
			if (!Crafting.craft(p, Items.ARROW)) break;
		}
		if (Gear.count(c, Items.TORCH) < 8 && Gear.count(c, s -> s.is(ItemTags.COALS)) > 2) Crafting.craft(p, Items.TORCH);
		Gear.equipArmor(p);
	}

	private static void armor(PartnerBrain brain, Item material, Item[] pieces) {
		PartnerEntity p = brain.p;
		for (Item piece : pieces) {
			ItemStack sample = new ItemStack(piece);
			var slot = sample.get(net.minecraft.core.component.DataComponents.EQUIPPABLE).slot();
			if (Gear.armorValue(p.getItemBySlot(slot)) >= Gear.armorValue(sample)) continue;
			if (Gear.has(p, s -> s.is(piece))) continue;
			announce(brain, piece);
		}
	}

	private static boolean announce(PartnerBrain brain, Item item) {
		if (!Crafting.craft(brain.p, item)) return false;
		brain.say("crafted", new ItemStack(item).getHoverName());
		return true;
	}
}
