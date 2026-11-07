package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Pocket crafting with real survival costs: the partner spends exactly the items a player would, and needs a
 * crafting table in its backpack for 3x3 recipes, like carrying one around. Smelting needs a furnace and fuel
 * (see {@link Smelter}). Recipes are the vanilla ones the progression uses.
 */
public final class Crafting {
	private record Need(Predicate<ItemStack> what, int count, Item plain) {}
	private record Recipe(Item out, int count, boolean table, List<Need> needs) {}

	private static final Predicate<ItemStack> PLANKS = s -> s.is(ItemTags.PLANKS);
	private static final Predicate<ItemStack> LOGS = s -> s.is(ItemTags.LOGS);
	private static final Predicate<ItemStack> WOOL = s -> s.is(ItemTags.WOOL);
	private static final Predicate<ItemStack> STICK = s -> s.is(Items.STICK);
	private static final Predicate<ItemStack> COBBLE = s -> s.is(ItemTags.STONE_TOOL_MATERIALS);
	private static final Predicate<ItemStack> IRON = s -> s.is(Items.IRON_INGOT);
	private static final Predicate<ItemStack> GOLD = s -> s.is(Items.GOLD_INGOT);
	private static final Predicate<ItemStack> DIAMOND = s -> s.is(Items.DIAMOND);
	private static final Predicate<ItemStack> SCRAP = s -> s.is(Items.NETHERITE_SCRAP);
	private static final Predicate<ItemStack> NETHERITE = s -> s.is(Items.NETHERITE_INGOT);
	private static final Predicate<ItemStack> URU = com.dantemorrigan.partner.compat.InfinityCompat::isUruIngot;
	private static final Predicate<ItemStack> COAL = s -> s.is(ItemTags.COALS);
	private static final Map<Item, Recipe> RECIPES = new LinkedHashMap<>();

	static {
		add(Items.STICK, 4, false, need(PLANKS, 2, Items.OAK_PLANKS));
		add(Items.CRAFTING_TABLE, 1, false, need(PLANKS, 4, Items.OAK_PLANKS));
		add(Items.TORCH, 4, false, need(COAL, 1, Items.COAL), need(STICK, 1, Items.STICK));
		add(Items.SHEARS, 1, false, need(IRON, 2, Items.IRON_INGOT));
		add(Items.BUCKET, 1, true, need(IRON, 3, Items.IRON_INGOT));
		add(Items.FISHING_ROD, 1, true, need(STICK, 3, Items.STICK), need(s -> s.is(Items.STRING), 2, Items.STRING));
		add(Items.BONE_MEAL, 3, false, need(s -> s.is(Items.BONE), 1, Items.BONE));
		add(Items.BREAD, 1, true, need(s -> s.is(Items.WHEAT), 3, Items.WHEAT));
		add(Items.CHEST, 1, true, need(PLANKS, 8, Items.OAK_PLANKS));
		add(Items.OAK_DOOR, 3, true, need(PLANKS, 6, Items.OAK_PLANKS));
		add(Items.OAK_FENCE, 3, true, need(PLANKS, 4, Items.OAK_PLANKS), need(STICK, 2, Items.STICK));
		add(Items.OAK_FENCE_GATE, 1, true, need(PLANKS, 2, Items.OAK_PLANKS), need(STICK, 4, Items.STICK));
		add(Items.WOODEN_HOE, 1, true, need(PLANKS, 2, Items.OAK_PLANKS), need(STICK, 2, Items.STICK));
		add(Items.STONE_HOE, 1, true, need(COBBLE, 2, Items.COBBLESTONE), need(STICK, 2, Items.STICK));
		add(Items.IRON_HOE, 1, true, need(IRON, 2, Items.IRON_INGOT), need(STICK, 2, Items.STICK));
		add(Items.CAMPFIRE, 1, true, need(STICK, 3, Items.STICK), need(COAL, 1, Items.COAL), need(LOGS, 3, Items.OAK_LOG));
		add(Items.WHITE_BED, 1, true, need(WOOL, 3, Items.WHITE_WOOL), need(PLANKS, 3, Items.OAK_PLANKS));
		add(Items.FURNACE, 1, true, need(COBBLE, 8, Items.COBBLESTONE));
		add(Items.WOODEN_PICKAXE, 1, true, need(PLANKS, 3, Items.OAK_PLANKS), need(STICK, 2, Items.STICK));
		add(Items.WOODEN_SWORD, 1, true, need(PLANKS, 2, Items.OAK_PLANKS), need(STICK, 1, Items.STICK));
		add(Items.WOODEN_AXE, 1, true, need(PLANKS, 3, Items.OAK_PLANKS), need(STICK, 2, Items.STICK));
		add(Items.STONE_PICKAXE, 1, true, need(COBBLE, 3, Items.COBBLESTONE), need(STICK, 2, Items.STICK));
		add(Items.STONE_SWORD, 1, true, need(COBBLE, 2, Items.COBBLESTONE), need(STICK, 1, Items.STICK));
		add(Items.STONE_AXE, 1, true, need(COBBLE, 3, Items.COBBLESTONE), need(STICK, 2, Items.STICK));
		add(Items.STONE_SHOVEL, 1, true, need(COBBLE, 1, Items.COBBLESTONE), need(STICK, 2, Items.STICK));
		add(Items.IRON_PICKAXE, 1, true, need(IRON, 3, Items.IRON_INGOT), need(STICK, 2, Items.STICK));
		add(Items.IRON_SWORD, 1, true, need(IRON, 2, Items.IRON_INGOT), need(STICK, 1, Items.STICK));
		add(Items.IRON_AXE, 1, true, need(IRON, 3, Items.IRON_INGOT), need(STICK, 2, Items.STICK));
		add(Items.IRON_HELMET, 1, true, need(IRON, 5, Items.IRON_INGOT));
		add(Items.IRON_CHESTPLATE, 1, true, need(IRON, 8, Items.IRON_INGOT));
		add(Items.IRON_LEGGINGS, 1, true, need(IRON, 7, Items.IRON_INGOT));
		add(Items.IRON_BOOTS, 1, true, need(IRON, 4, Items.IRON_INGOT));
		add(Items.GOLDEN_HELMET, 1, true, need(GOLD, 5, Items.GOLD_INGOT));
		add(Items.GOLDEN_BOOTS, 1, true, need(GOLD, 4, Items.GOLD_INGOT));
		add(Items.SHIELD, 1, true, need(PLANKS, 6, Items.OAK_PLANKS), need(IRON, 1, Items.IRON_INGOT));
		add(Items.BOW, 1, true, need(STICK, 3, Items.STICK), need(s -> s.is(Items.STRING), 3, Items.STRING));
		add(Items.ARROW, 4, true, need(s -> s.is(Items.FLINT), 1, Items.FLINT), need(STICK, 1, Items.STICK), need(s -> s.is(Items.FEATHER), 1, Items.FEATHER));
		add(Items.DIAMOND_PICKAXE, 1, true, need(DIAMOND, 3, Items.DIAMOND), need(STICK, 2, Items.STICK));
		add(Items.DIAMOND_SWORD, 1, true, need(DIAMOND, 2, Items.DIAMOND), need(STICK, 1, Items.STICK));
		add(Items.DIAMOND_AXE, 1, true, need(DIAMOND, 3, Items.DIAMOND), need(STICK, 2, Items.STICK));
		add(Items.DIAMOND_HELMET, 1, true, need(DIAMOND, 5, Items.DIAMOND));
		add(Items.DIAMOND_CHESTPLATE, 1, true, need(DIAMOND, 8, Items.DIAMOND));
		add(Items.DIAMOND_LEGGINGS, 1, true, need(DIAMOND, 7, Items.DIAMOND));
		add(Items.DIAMOND_BOOTS, 1, true, need(DIAMOND, 4, Items.DIAMOND));
		add(Items.NETHERITE_INGOT, 1, true, need(SCRAP, 4, Items.NETHERITE_SCRAP), need(GOLD, 4, Items.GOLD_INGOT));
		add(Items.NETHERITE_PICKAXE, 1, true, need(s -> s.is(Items.DIAMOND_PICKAXE), 1, Items.DIAMOND_PICKAXE), need(NETHERITE, 1, Items.NETHERITE_INGOT));
		add(Items.NETHERITE_SWORD, 1, true, need(s -> s.is(Items.DIAMOND_SWORD), 1, Items.DIAMOND_SWORD), need(NETHERITE, 1, Items.NETHERITE_INGOT));
		add(Items.NETHERITE_HELMET, 1, true, need(s -> s.is(Items.DIAMOND_HELMET), 1, Items.DIAMOND_HELMET), need(NETHERITE, 1, Items.NETHERITE_INGOT));
		add(Items.NETHERITE_CHESTPLATE, 1, true, need(s -> s.is(Items.DIAMOND_CHESTPLATE), 1, Items.DIAMOND_CHESTPLATE), need(NETHERITE, 1, Items.NETHERITE_INGOT));
		add(Items.NETHERITE_LEGGINGS, 1, true, need(s -> s.is(Items.DIAMOND_LEGGINGS), 1, Items.DIAMOND_LEGGINGS), need(NETHERITE, 1, Items.NETHERITE_INGOT));
		add(Items.NETHERITE_BOOTS, 1, true, need(s -> s.is(Items.DIAMOND_BOOTS), 1, Items.DIAMOND_BOOTS), need(NETHERITE, 1, Items.NETHERITE_INGOT));
	}

	public static void initModRecipes() {
		Item uruPick = com.dantemorrigan.partner.compat.InfinityCompat.item("uru_pickaxe");
		if (uruPick != Items.AIR) add(uruPick, 1, true, need(URU, 3, Items.AIR), need(STICK, 2, Items.STICK));
		Item uruSword = com.dantemorrigan.partner.compat.InfinityCompat.item("uru_sword");
		if (uruSword != Items.AIR) add(uruSword, 1, true, need(URU, 2, Items.AIR), need(STICK, 1, Items.STICK));
		Item uruHelm = com.dantemorrigan.partner.compat.InfinityCompat.item("uru_helmet");
		if (uruHelm != Items.AIR) add(uruHelm, 1, true, need(URU, 5, Items.AIR));
		Item uruChest = com.dantemorrigan.partner.compat.InfinityCompat.item("uru_chestplate");
		if (uruChest != Items.AIR) add(uruChest, 1, true, need(URU, 8, Items.AIR));
		Item uruLegs = com.dantemorrigan.partner.compat.InfinityCompat.item("uru_leggings");
		if (uruLegs != Items.AIR) add(uruLegs, 1, true, need(URU, 7, Items.AIR));
		Item uruBoots = com.dantemorrigan.partner.compat.InfinityCompat.item("uru_boots");
		if (uruBoots != Items.AIR) add(uruBoots, 1, true, need(URU, 4, Items.AIR));
	}

	private static Need need(Predicate<ItemStack> what, int count, Item plain) {
		return new Need(what, count, plain);
	}

	private static void add(Item out, int count, boolean table, Need... needs) {
		RECIPES.put(out, new Recipe(out, count, table, List.of(needs)));
	}

	/** Crafts the item if possible, crafting planks, sticks and the table on the way. */
	/**
	 * Crafts the item from the backpack (planks and sticks on the way). 3×3 recipes only work next to a crafting
	 * table standing in the world; {@link CraftJob} finds or places one and works at it.
	 */
	public static boolean craft(PartnerEntity p, Item target) {
		if (needsTable(target) && !tableNearby(p)) return false;
		SimpleContainer c = p.backpack();
		unequipDiamondForUpgrade(p, target, c, false);
		java.util.List<ItemStack> overflow = new java.util.ArrayList<>();
		if (!make(c, target, 0, new boolean[]{true}, overflow)) {
			Gear.equipArmor(p);
			return false;
		}
		if (p.level() instanceof net.minecraft.server.level.ServerLevel level) for (ItemStack rest : overflow) p.spawnAtLocation(level, rest);
		Gear.equipArmor(p);
		p.playSound(SoundEvents.VILLAGER_WORK_TOOLSMITH, 0.4f, 1.2f);
		p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		return true;
	}

	/** Whether the partner could craft it from what it carries (counting a table it would have to make first). */
	public static boolean canMake(PartnerEntity p, Item target) {
		SimpleContainer src = p.backpack();
		SimpleContainer copy = new SimpleContainer(src.getContainerSize() + 9);
		for (int i = 0; i < src.getContainerSize(); i++) copy.setItem(i, src.getItem(i).copy());
		unequipDiamondForUpgrade(p, target, copy, true);
		return make(copy, target, 0, new boolean[]{tableNearby(p)}, new java.util.ArrayList<>());
	}

	private static void unequipDiamondForUpgrade(PartnerEntity p, Item target, SimpleContainer dest, boolean copyOnly) {
		Recipe r = RECIPES.get(target);
		if (r == null) return;
		for (Need n : r.needs()) {
			if (Gear.count(dest, n.what()) >= n.count()) continue;
			for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
					net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.entity.EquipmentSlot.HEAD,
					net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS,
					net.minecraft.world.entity.EquipmentSlot.FEET}) {
				ItemStack eq = p.getItemBySlot(slot);
				if (n.what().test(eq)) {
					dest.addItem(eq.copy());
					if (!copyOnly) p.setItemSlot(slot, ItemStack.EMPTY);
					break;
				}
			}
		}
	}

	public static boolean needsTable(Item target) {
		Recipe r = RECIPES.get(target);
		return r != null && r.table();
	}

	/** A crafting table block within arm's reach of the partner. */
	public static boolean tableNearby(PartnerEntity p) {
		return findTable(p, 4) != null;
	}

	public static net.minecraft.core.@org.jspecify.annotations.Nullable BlockPos findTable(PartnerEntity p, int radius) {
		var level = p.level();
		net.minecraft.core.BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		var center = p.blockPosition();
		for (var pos : net.minecraft.core.BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, 3, radius))) {
			if (!level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) continue;
			double d = p.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos));
			if (radius <= 4 && d > BlockBreaker.REACH * BlockBreaker.REACH) continue;
			if (d < bestD) {
				bestD = d;
				best = pos.immutable();
			}
		}
		return best;
	}

	/** Pure crafting on a container. {@code table[0]}: a table is available (else one must be made from planks). */
	private static boolean make(SimpleContainer c, Item target, int depth, boolean[] table, java.util.List<ItemStack> overflow) {
		if (depth > 5) return false;
		if (target == Items.OAK_PLANKS) return planks(c, overflow);
		Recipe r = RECIPES.get(target);
		if (r == null) return false;
		if (r.table() && !table[0]) {
			if (Gear.count(c, Items.CRAFTING_TABLE) == 0 && !make(c, Items.CRAFTING_TABLE, depth + 1, table, overflow)) return false;
			table[0] = true; // it gets placed and used
		}
		for (Need n : r.needs()) {
			int guard = 0;
			while (Gear.count(c, n.what()) < n.count() && guard++ < 8) {
				if (n.plain() == target || !make(c, n.plain(), depth + 1, table, overflow)) return false;
			}
			if (Gear.count(c, n.what()) < n.count()) return false;
		}
		for (Need n : r.needs()) Gear.take(c, n.what(), n.count());
		ItemStack rest = c.addItem(new ItemStack(r.out(), r.count()));
		if (!rest.isEmpty()) overflow.add(rest);
		return true;
	}

	/** One log becomes four planks of the same wood. */
	public static boolean makePlanks(PartnerEntity p) {
		java.util.List<ItemStack> overflow = new java.util.ArrayList<>();
		boolean ok = planks(p.backpack(), overflow);
		if (p.level() instanceof net.minecraft.server.level.ServerLevel level) for (ItemStack rest : overflow) p.spawnAtLocation(level, rest);
		return ok;
	}

	private static boolean planks(SimpleContainer c, java.util.List<ItemStack> overflow) {
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.is(ItemTags.LOGS)) continue;
			Item planks = planksFor(s);
			s.shrink(1);
			ItemStack rest = c.addItem(new ItemStack(planks, 4));
			if (!rest.isEmpty()) overflow.add(rest);
			c.setChanged();
			return true;
		}
		return false;
	}

	private static Item planksFor(ItemStack log) {
		Identifier id = BuiltInRegistries.ITEM.getKey(log.getItem());
		String path = id.getPath().replace("stripped_", "");
		for (String suffix : new String[]{"_log", "_wood", "_stem", "_hyphae"}) {
			if (path.endsWith(suffix)) {
				Item planks = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(id.getNamespace(), path.substring(0, path.length() - suffix.length()) + "_planks"));
				if (planks != Items.AIR) return planks;
			}
		}
		return Items.OAK_PLANKS;
	}

	/** What the partner could craft right now that it does not have yet, in upgrade order. */
	public static List<Item> upgrades(PartnerEntity p) {
		List<Item> list = new ArrayList<>();
		int pick = 0;
		for (int i = 0; i < p.backpack().getContainerSize(); i++) pick = Math.max(pick, Gear.pickTier(p.backpack().getItem(i)));
		pick = Math.max(pick, Gear.pickTier(p.getMainHandItem()));
		if (pick < 4 && Gear.count(p.backpack(), DIAMOND) >= 3) list.add(Items.DIAMOND_PICKAXE);
		if (pick < 3 && Gear.count(p.backpack(), IRON) >= 3) list.add(Items.IRON_PICKAXE);
		if (pick < 2 && Gear.count(p.backpack(), COBBLE) >= 3) list.add(Items.STONE_PICKAXE);
		if (pick < 1) list.add(Items.WOODEN_PICKAXE);
		return list;
	}

	private Crafting() {}
}
