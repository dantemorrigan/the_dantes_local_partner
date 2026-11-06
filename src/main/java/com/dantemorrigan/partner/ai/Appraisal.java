package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerChat;
import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.compat.InfinityCompat;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the partner makes of an item the owner gives it (by hand, through the backpack screen, or thrown on the
 * ground): it looks at it, compares it with what it already has, thanks, says what it is good for, and puts it to
 * use (better armor goes on at once, better weapons and tools get picked automatically when needed).
 *
 * <p>Items are compared "against the rest": the received stack itself is left out of the baseline, so a new sword
 * is measured against the best sword it had before.
 */
public final class Appraisal {
	private Appraisal() {}

	/** Items of the owner's that must not stay with the partner (The Dante's Infinity stones, gauntlet...). */
	public static boolean refuses(ItemStack s) {
		return InfinityCompat.isProtectedItem(s);
	}

	/**
	 * Called after {@code received} (a copy of what was given) has been put into the backpack.
	 * Says one line per kind of item (at most four, one after another) and applies what makes sense at once.
	 */
	public static void received(PartnerBrain brain, ServerPlayer owner, List<ItemStack> received) {
		PartnerEntity p = brain.p;
		List<Line> lines = new ArrayList<>();
		for (ItemStack stack : merge(received)) {
			Line line = analyze(brain, stack);
			if (line != null) lines.add(line);
		}
		if (lines.isEmpty()) return;
		lines.sort((a, b) -> b.weight - a.weight);
		int n = Math.min(4, lines.size());
		for (int i = 0; i < n; i++) {
			Line line = lines.get(i);
			Runnable say = () -> p.speakComponent(line.tone, line.message);
			if (i == 0) say.run();
			else brain.later(say, 30 * i);
		}
		if (lines.size() > n) brain.later(() -> p.speak("appraise_more", lines.size() - n), 30 * n);
		p.getLookControl().setLookAt(owner, 30f, 30f);
		p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		brain.onPickedUp();
	}

	private record Line(PartnerChat.Tone tone, Component message, int weight) {}

	private static List<ItemStack> merge(List<ItemStack> in) {
		List<ItemStack> out = new ArrayList<>();
		for (ItemStack s : in) {
			if (s.isEmpty()) continue;
			boolean merged = false;
			for (ItemStack o : out) {
				if (ItemStack.isSameItemSameComponents(o, s)) {
					o.grow(s.getCount());
					merged = true;
					break;
				}
			}
			if (!merged) out.add(s.copy());
		}
		return out;
	}

	private static Component name(ItemStack s) {
		return s.getHoverName();
	}

	private static String num(double v) {
		return String.format(Locale.ROOT, "%.1f", v).replace(".0", "");
	}

	private static Component key(String k, Object... args) {
		return Component.translatable("partner.local_partner.appraise." + k, args);
	}

	private static String enchanted(ItemStack s) {
		return s.isEnchanted() ? "_ench" : "";
	}

	private static Line analyze(PartnerBrain brain, ItemStack s) {
		PartnerEntity p = brain.p;
		Item item = s.getItem();

		// ---------------------------------------------------------------- armor
		if (Gear.isArmor(s)) {
			Equippable eq = s.get(DataComponents.EQUIPPABLE);
			EquipmentSlot slot = eq.slot();
			double mine = Gear.armorValue(s) + Gear.enchantBonus(s);
			double worn = Math.max(Gear.armorValue(p.getItemBySlot(slot)) + Gear.enchantBonus(p.getItemBySlot(slot)), bestOther(p, s, Gear::isArmor, slot));
			Component slotName = Component.translatable("partner.local_partner.appraise.slot." + slot.getName());
			if (mine > worn + 1e-6) {
				return new Line(PartnerChat.Tone.GOOD, key(worn <= 0 ? "armor_first" + enchanted(s) : "armor_better" + enchanted(s), name(s), slotName, num(mine / 2), num(worn / 2)), 90);
			}
			return new Line(PartnerChat.Tone.INFO, key("armor_worse", name(s), num(mine / 2), num(worn / 2)), 40);
		}
		// ---------------------------------------------------------------- weapons
		if (Gear.isSword(s) || Gear.isAxe(s)) {
			double mine = Gear.attackDamage(s) + Gear.enchantBonus(s) * 0.5;
			double best = Math.max(Gear.attackDamage(p.getMainHandItem()), bestOther(p, s, x -> Gear.isSword(x) || Gear.isAxe(x), null));
			if (best <= 1.01) return new Line(PartnerChat.Tone.GOOD, key("weapon_first" + enchanted(s), name(s), num(mine)), 100);
			if (mine > best + 1e-6) return new Line(PartnerChat.Tone.GOOD, key("weapon_better" + enchanted(s), name(s), num(mine), num(best)), 95);
			if (Gear.isAxe(s) && !Gear.has(p, x -> Gear.isAxe(x) && x != s)) {
				return new Line(PartnerChat.Tone.GOOD, key("axe_wood", name(s)), 70);
			}
			return new Line(PartnerChat.Tone.INFO, key("weapon_worse", name(s), num(mine), num(best)), 45);
		}
		// ---------------------------------------------------------------- pickaxe / tools
		if (Gear.isPickaxe(s)) {
			int tier = Gear.pickTier(s);
			int best = Math.max(Gear.pickTier(p.getMainHandItem()), (int) bestOther(p, s, Gear::isPickaxe, null, Gear::pickTier));
			if (tier > best) {
				return new Line(PartnerChat.Tone.GOOD, key("pick_better", name(s), Component.translatable("partner.local_partner.appraise.unlock." + Math.min(tier, 5))), 92);
			}
			return new Line(PartnerChat.Tone.INFO, key("pick_worse", name(s)), 42);
		}
		if (Gear.isShovel(s) || s.is(ItemTags.HOES)) return new Line(PartnerChat.Tone.INFO, key("tool_other", name(s)), 35);
		// ---------------------------------------------------------------- ranged and shield
		if (Gear.isShield(s)) {
			boolean had = Gear.has(p, x -> Gear.isShield(x) && x != s) || bestOther(p, s, Gear::isShield, null) > 0;
			return new Line(PartnerChat.Tone.GOOD, key(had ? "shield_spare" : "shield_new", name(s)), had ? 38 : 85);
		}
		if (Gear.isBow(s)) {
			return new Line(PartnerChat.Tone.GOOD, key(Gear.arrows(p) > 0 ? "bow_with_arrows" : "bow_no_arrows", name(s)), 80);
		}
		if (item == Items.ARROW || s.is(ItemTags.ARROWS)) {
			return new Line(PartnerChat.Tone.GOOD, key(Gear.has(p, Gear::isBow) ? "arrows_have_bow" : "arrows_no_bow", s.getCount()), 60);
		}
		// ---------------------------------------------------------------- food
		if (Gear.isFood(s)) {
			int points = Gear.nutrition(s) * s.getCount();
			return new Line(PartnerChat.Tone.HELP, key("food", name(s), s.getCount(), points), 55);
		}
		// ---------------------------------------------------------------- materials
		if (item == Items.IRON_INGOT || item == Items.DIAMOND || item == Items.RAW_IRON || item == Items.COAL || item == Items.CHARCOAL
				|| s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || s.is(ItemTags.STONE_TOOL_MATERIALS) || item == Items.STRING || item == Items.GOLD_INGOT) {
			List<Item> can = AutoJob.plannedCrafts(brain);
			if (!can.isEmpty()) {
				Component list = Component.empty();
				for (int i = 0; i < Math.min(3, can.size()); i++) {
					if (i > 0) ((net.minecraft.network.chat.MutableComponent) list).append(", ");
					((net.minecraft.network.chat.MutableComponent) list).append(new ItemStack(can.get(i)).getHoverName());
				}
				return new Line(PartnerChat.Tone.GOOD, key("material_craft", name(s), s.getCount(), list), 65);
			}
			return new Line(PartnerChat.Tone.INFO, key("material", name(s), s.getCount()), 30);
		}
		if (item == Items.CRAFTING_TABLE) return new Line(PartnerChat.Tone.GOOD, key("table", name(s)), 50);
		if (item == Items.FURNACE) return new Line(PartnerChat.Tone.GOOD, key("furnace", name(s)), 50);
		if (item == Items.TORCH) return new Line(PartnerChat.Tone.INFO, key("torch", s.getCount()), 33);
		if (s.is(ItemTags.SAPLINGS)) return new Line(PartnerChat.Tone.INFO, key("sapling", name(s)), 28);
		if (s.isEnchanted()) return new Line(PartnerChat.Tone.INFO, key("enchanted_other", name(s)), 32);
		return new Line(PartnerChat.Tone.INFO, key("unknown", name(s), s.getCount()), 10);
	}

	/** The best value of something the partner already has, not counting the received stack itself. */
	private static double bestOther(PartnerEntity p, ItemStack received, java.util.function.Predicate<ItemStack> kind, EquipmentSlot slot) {
		return bestOther(p, received, kind, slot, s -> Gear.isArmor(s) ? Gear.armorValue(s) + Gear.enchantBonus(s) : Gear.attackDamage(s) + Gear.enchantBonus(s) * 0.5);
	}

	private static double bestOther(PartnerEntity p, ItemStack received, java.util.function.Predicate<ItemStack> kind, EquipmentSlot slot,
			java.util.function.ToDoubleFunction<ItemStack> value) {
		double best = 0;
		int skip = received.getCount();
		var bag = p.backpack();
		for (int i = 0; i < bag.getContainerSize(); i++) {
			ItemStack s = bag.getItem(i);
			if (s.isEmpty() || !kind.test(s)) continue;
			if (slot != null && (!Gear.isArmor(s) || s.get(DataComponents.EQUIPPABLE).slot() != slot)) continue;
			if (skip > 0 && ItemStack.isSameItemSameComponents(s, received)) {
				int take = Math.min(skip, s.getCount());
				skip -= take;
				if (take >= s.getCount()) continue; // this slot is entirely the received stack
			}
			best = Math.max(best, value.applyAsDouble(s));
		}
		return best;
	}
}
