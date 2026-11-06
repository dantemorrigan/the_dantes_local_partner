package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.function.Predicate;

/** Backpack and equipment helpers: food, best tool/weapon/armor, counting and taking items. */
public final class Gear {
	private static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
			Items.PUFFERFISH, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT, Items.CHICKEN);
	private static final BlockState STONE = Blocks.STONE.defaultBlockState();
	private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();
	private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

	public static boolean isFood(ItemStack s) {
		return s.has(DataComponents.FOOD) && !BAD_FOOD.contains(s.getItem());
	}

	public static int nutrition(ItemStack s) {
		FoodProperties f = s.get(DataComponents.FOOD);
		return f == null ? 0 : f.nutrition();
	}

	/** The food that best fits the hunger: the smallest item that fills it, else the most nourishing one. */
	public static int bestFoodSlot(SimpleContainer c, int missing) {
		int best = -1, bestScore = Integer.MIN_VALUE;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!isFood(s) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) continue;
			int n = nutrition(s);
			int score = n >= missing ? 100 - (n - missing) : n;
			if (s.is(Items.GOLDEN_APPLE)) score -= 50; // save golden apples for emergencies
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	public static int foodPoints(SimpleContainer c) {
		int total = 0;
		for (int i = 0; i < c.getContainerSize(); i++) if (isFood(c.getItem(i))) total += nutrition(c.getItem(i)) * c.getItem(i).getCount();
		return total;
	}

	public static boolean isPickaxe(ItemStack s) { return s.is(ItemTags.PICKAXES); }
	public static boolean isAxe(ItemStack s) { return s.is(ItemTags.AXES); }
	public static boolean isShovel(ItemStack s) { return s.is(ItemTags.SHOVELS); }
	public static boolean isSword(ItemStack s) { return s.is(ItemTags.SWORDS); }

	public static boolean isTool(ItemStack s) {
		return isPickaxe(s) || isAxe(s) || isShovel(s) || isSword(s) || s.is(ItemTags.HOES);
	}

	public static boolean isArmor(ItemStack s) {
		Equippable e = s.get(DataComponents.EQUIPPABLE);
		return e != null && e.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
	}

	/** 0 none, 1 wood/gold, 2 stone, 3 iron, 4 diamond, 5 netherite (by mining speed on stone). */
	public static int pickTier(ItemStack s) {
		if (!isPickaxe(s)) return 0;
		float speed = s.getDestroySpeed(STONE);
		if (speed >= 12) return 1; // gold: fast but weak
		if (speed >= 9) return 5;
		if (speed >= 8) return 4;
		if (speed >= 6) return 3;
		return speed >= 4 ? 2 : 1;
	}

	/** Best pickaxe tier the partner owns (hand or backpack). */
	public static int pickTierOf(PartnerEntity p) {
		int best = pickTier(p.getMainHandItem());
		for (int i = 0; i < p.backpack().getContainerSize(); i++) best = Math.max(best, pickTier(p.backpack().getItem(i)));
		return best;
	}

	/** A small bonus for enchanted gear, so it is preferred over a plain item of the same tier. */
	public static double enchantBonus(ItemStack s) {
		return s.isEnchanted() ? 1.0 : 0.0;
	}

	public static double attackDamage(ItemStack s) {
		ItemAttributeModifiers mods = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
	}

	public static double armorValue(ItemStack s) {
		Equippable e = s.get(DataComponents.EQUIPPABLE);
		if (e == null) return 0;
		ItemAttributeModifiers mods = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.compute(Attributes.ARMOR, 0, e.slot()) * 2 + mods.compute(Attributes.ARMOR_TOUGHNESS, 0, e.slot());
	}

	/** How good the stack is for breaking this block (0 = useless). */
	public static float toolScore(ItemStack s, BlockState state) {
		if (s.isEmpty()) return 1f;
		float speed = s.getDestroySpeed(state);
		if (state.requiresCorrectToolForDrops() && !s.isCorrectToolForDrops(state)) return 0.5f;
		return speed;
	}

	/** What kind of tool a block wants: used when nothing better is known. */
	public static float generalScore(ItemStack s) {
		return Math.max(s.getDestroySpeed(STONE), Math.max(s.getDestroySpeed(LOG), s.getDestroySpeed(DIRT)));
	}

	public static int count(SimpleContainer c, Predicate<ItemStack> p) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) if (p.test(c.getItem(i))) n += c.getItem(i).getCount();
		return n;
	}

	public static int count(SimpleContainer c, Item item) {
		return count(c, s -> s.is(item));
	}

	public static boolean has(PartnerEntity p, Predicate<ItemStack> pred) {
		if (pred.test(p.getMainHandItem())) return true;
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && pred.test(p.getItemBySlot(slot))) return true;
		}
		return count(p.backpack(), pred) > 0;
	}

	/** Removes up to {@code amount} matching items; returns how many were removed. */
	public static int take(SimpleContainer c, Predicate<ItemStack> p, int amount) {
		int left = amount;
		for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
			ItemStack s = c.getItem(i);
			if (!p.test(s)) continue;
			int n = Math.min(left, s.getCount());
			s.shrink(n);
			left -= n;
		}
		c.setChanged();
		return amount - left;
	}

	/** Moves the best backpack item for {@code score} into the main hand (old main-hand item goes to the backpack). */
	public static void hold(PartnerEntity p, java.util.function.ToDoubleFunction<ItemStack> score) {
		SimpleContainer c = p.backpack();
		ItemStack current = p.getMainHandItem();
		double bestScore = current.isEmpty() ? score.applyAsDouble(ItemStack.EMPTY) : score.applyAsDouble(current);
		int best = -1;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (s.isEmpty()) continue;
			double v = score.applyAsDouble(s);
			if (v > bestScore + 1e-6) {
				bestScore = v;
				best = i;
			}
		}
		if (best < 0) return;
		ItemStack chosen = c.removeItemNoUpdate(best);
		p.setItemSlot(EquipmentSlot.MAINHAND, chosen);
		if (!current.isEmpty()) {
			ItemStack rest = c.addItem(current);
			if (!rest.isEmpty()) p.spawnAtLocation((net.minecraft.server.level.ServerLevel) p.level(), rest);
		}
		c.setChanged();
	}

	public static void holdWeapon(PartnerEntity p) {
		hold(p, s -> s.isEmpty() ? 1.0 : (isSword(s) || isAxe(s)) ? attackDamage(s) + (isSword(s) ? 0.5 : 0) + enchantBonus(s) * 0.5 : 0.5);
	}

	public static void holdToolFor(PartnerEntity p, BlockState state) {
		hold(p, s -> isTool(s) || s.isEmpty() ? toolScore(s, state) - (isSword(s) ? 0.6 : 0) : 0.1);
	}

	public static boolean isShield(ItemStack s) {
		return s.is(Items.SHIELD);
	}

	public static boolean isBow(ItemStack s) {
		return s.is(Items.BOW);
	}

	public static int arrows(PartnerEntity p) {
		return count(p.backpack(), s -> s.is(Items.ARROW));
	}

	/** Puts on the best armor from the backpack, and a shield in the off hand. */
	public static void equipArmor(PartnerEntity p) {
		SimpleContainer c = p.backpack();
		if (p.getOffhandItem().isEmpty() && !p.isEating()) {
			for (int i = 0; i < c.getContainerSize(); i++) {
				if (isShield(c.getItem(i))) {
					p.setItemSlot(EquipmentSlot.OFFHAND, c.removeItemNoUpdate(i));
					break;
				}
			}
		}
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!isArmor(s)) continue;
			EquipmentSlot slot = s.get(DataComponents.EQUIPPABLE).slot();
			ItemStack worn = p.getItemBySlot(slot);
			if (armorValue(s) + enchantBonus(s) <= armorValue(worn) + enchantBonus(worn)) continue;
			p.setItemSlot(slot, c.removeItemNoUpdate(i));
			if (!worn.isEmpty()) c.setItem(i, worn);
		}
		c.setChanged();
	}

	public static Identifier id(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem());
	}

	private Gear() {}
}
