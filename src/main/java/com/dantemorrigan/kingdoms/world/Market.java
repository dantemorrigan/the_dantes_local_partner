package com.dantemorrigan.kingdoms.world;

import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.Trade;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * What a kingdom's merchants trade with the player, with gold ingots as money. Prices follow the kingdom's real
 * scarcity ({@link Trade#price}), stock limits how much is on offer, and every deal moves the kingdom's own treasury
 * and stores, so the player's trading changes the economy the simulation runs on.
 */
public final class Market {
	/** good type (0 food, 1 wood, 2 stone, 3 iron) of an item, or -1. */
	static int good(Item item) {
		if (item == Items.BREAD || item == Items.WHEAT || item == Items.COOKED_BEEF || item == Items.CARROT || item == Items.POTATO) return 0;
		if (item == Items.OAK_LOG || item == Items.SPRUCE_LOG || item == Items.BIRCH_LOG) return 1;
		if (item == Items.COBBLESTONE || item == Items.STONE) return 2;
		if (item == Items.IRON_INGOT) return 3;
		return -1;
	}

	static float stock(Kingdom k, int type) {
		return switch (type) {
			case 0 -> k.food;
			case 1 -> k.wood;
			case 2 -> k.stone;
			default -> k.iron;
		};
	}

	private static int gold(float v) {
		return Math.max(1, Math.round(v));
	}

	public static MerchantOffers offers(Kingdom k) {
		MerchantOffers list = new MerchantOffers();
		Item[] sell = {Items.BREAD, Items.OAK_LOG, Items.COBBLESTONE, Items.IRON_INGOT};
		int[] sellAmt = {8, 16, 16, 4};
		for (int t = 0; t < 4; t++) {
			int uses = (int) Math.min(12, (stock(k, t) - 15) / sellAmt[t]);
			if (uses <= 0) continue;
			int cost = gold(sellAmt[t] * Trade.price(k, t) * 1.2f);
			list.add(new MerchantOffer(new ItemCost(Items.GOLD_INGOT, cost), new ItemStack(sell[t], sellAmt[t]), uses, 2, 0.05f));
		}
		if (k.era >= 1 && k.iron > 10) list.add(new MerchantOffer(new ItemCost(Items.GOLD_INGOT, 2 + k.era), new ItemStack(Items.ARROW, 24), 6, 2, 0.05f));
		if (k.era >= 1 && k.iron > 15) list.add(new MerchantOffer(new ItemCost(Items.GOLD_INGOT, 3 + k.era), new ItemStack(k.era >= 3 ? Items.DIAMOND_SWORD : Items.IRON_SWORD), 3, 5, 0.05f));
		if (k.era >= 2 && k.iron > 25) list.add(new MerchantOffer(new ItemCost(Items.GOLD_INGOT, 8), new ItemStack(Items.IRON_CHESTPLATE), 2, 5, 0.05f));
		Item[] buy = {Items.WHEAT, Items.OAK_LOG, Items.COBBLESTONE, Items.IRON_INGOT};
		int[] buyAmt = {16, 16, 32, 4};
		for (int t = 0; t < 4; t++) {
			if (k.gold < 8) break;
			int pay = gold(buyAmt[t] * Trade.price(k, t) * 0.8f);
			list.add(new MerchantOffer(new ItemCost(buy[t], buyAmt[t]), new ItemStack(Items.GOLD_INGOT, pay), (int) Math.min(12, k.gold / Math.max(1, pay)), 2, 0.05f));
		}
		return list;
	}

	/** A deal was made: move the kingdom's gold and stores. */
	public static void applied(Kingdom k, MerchantOffer o) {
		ItemStack result = o.getResult();
		ItemCost cost = o.getItemCostA();
		Item costItem = cost.item().value();
		if (costItem == Items.GOLD_INGOT) { // the player bought goods
			k.gold += cost.count();
			int t = good(result.getItem());
			if (t >= 0) add(k, t, -result.getCount());
			else if (result.getItem() == Items.ARROW || result.getItem() == Items.IRON_SWORD || result.getItem() == Items.DIAMOND_SWORD || result.getItem() == Items.IRON_CHESTPLATE) k.iron = Math.max(0, k.iron - 2);
		} else if (result.getItem() == Items.GOLD_INGOT) { // the player sold goods
			k.gold = Math.max(0, k.gold - result.getCount());
			int t = good(costItem);
			if (t >= 0) add(k, t, cost.count());
		}
	}

	static void add(Kingdom k, int type, float v) {
		switch (type) {
			case 0 -> k.food = Math.max(0, k.food + v);
			case 1 -> k.wood = Math.max(0, k.wood + v);
			case 2 -> k.stone = Math.max(0, k.stone + v);
			default -> k.iron = Math.max(0, k.iron + v);
		}
	}

	private Market() {}
}
