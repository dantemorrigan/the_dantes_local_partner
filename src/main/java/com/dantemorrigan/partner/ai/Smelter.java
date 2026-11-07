package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A "camping furnace": when the partner carries a furnace and fuel it smelts raw ores and raw food from its backpack
 * in the background at the normal furnace speed (10 s per item), using the world's real smelting recipes and fuel.
 * Only ores (anything that smelts into an ingot) and food are smelted, so it never wastes cobblestone or sand.
 */
public final class Smelter {
	private static final int COOK_TIME = 200;
	private int burn, progress;

	public void tick(PartnerEntity p, ServerLevel level) {
		SimpleContainer c = p.backpack();
		if (burn > 0) burn--;
		if (Gear.count(c, Items.FURNACE) == 0) {
			progress = 0;
			return;
		}
		int input = findInput(c, level);
		if (input < 0) {
			progress = 0;
			return;
		}
		if (burn <= 0 && !refuel(c, level)) return;
		if (progress == 0) p.brain().report("smelt_start", c.getItem(input).getHoverName());
		if (++progress < COOK_TIME) return;
		progress = 0;
		ItemStack raw = c.getItem(input);
		var recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(raw), level);
		if (recipe.isEmpty()) return;
		ItemStack out = recipe.get().value().assemble(new SingleRecipeInput(raw)).copy();
		raw.shrink(1);
		p.brain().report("smelt_done", out.getHoverName(), Gear.count(c, out.getItem()) + out.getCount());
		ItemStack rest = c.addItem(out);
		if (!rest.isEmpty()) p.spawnAtLocation(level, rest);
		c.setChanged();
		p.playSound(SoundEvents.FURNACE_FIRE_CRACKLE, 0.3f, 1f);
	}

	public boolean busy() {
		return progress > 0;
	}

	public static boolean hasSmeltable(SimpleContainer c, ServerLevel level) {
		return findInput(c, level) >= 0;
	}

	public static int countSmeltable(SimpleContainer c, ServerLevel level) {
		int total = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty() && isSmeltable(s, level)) total += s.getCount();
		}
		return total;
	}

	public boolean ensureBurning(SimpleContainer c, ServerLevel level) {
		return burn > 0 || refuel(c, level);
	}

	/** Smelts one item immediately (called by {@link SmeltJob} after cooking at a placed furnace). */
	public boolean finishOne(PartnerEntity p, ServerLevel level) {
		SimpleContainer c = p.backpack();
		int input = findInput(c, level);
		if (input < 0 || (burn <= 0 && !refuel(c, level))) return false;
		progress = 0;
		ItemStack raw = c.getItem(input);
		var recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(raw), level);
		if (recipe.isEmpty()) return false;
		ItemStack out = recipe.get().value().assemble(new SingleRecipeInput(raw)).copy();
		raw.shrink(1);
		p.brain().report("smelt_done", out.getHoverName(), Gear.count(c, out.getItem()) + out.getCount());
		ItemStack rest = c.addItem(out);
		if (!rest.isEmpty()) p.spawnAtLocation(level, rest);
		c.setChanged();
		p.playSound(SoundEvents.FURNACE_FIRE_CRACKLE, 0.4f, 1f);
		return true;
	}

	private static boolean isSmeltable(ItemStack s, ServerLevel level) {
		var recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(s), level);
		if (recipe.isEmpty()) return false;
		ItemStack out = recipe.get().value().assemble(new SingleRecipeInput(s));
		String path = Gear.id(out).getPath();
		boolean ore = (path.endsWith("_ingot") || out.is(Items.NETHERITE_SCRAP)
				|| com.dantemorrigan.partner.compat.InfinityCompat.isUruIngot(out)) && !Gear.id(s).getPath().endsWith("_ingot");
		boolean food = Gear.isFood(out) && !Gear.isFood(s) || s.has(net.minecraft.core.component.DataComponents.FOOD) && Gear.nutrition(out) > Gear.nutrition(s);
		return ore || food;
	}

	private static int findInput(SimpleContainer c, ServerLevel level) {
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty() && isSmeltable(s, level)) return i;
		}
		return -1;
	}

	/** Coal first; planks and logs only when there are plenty left for crafting. */
	private boolean refuel(SimpleContainer c, ServerLevel level) {
		int planks = Gear.count(c, s -> s.is(ItemTags.PLANKS)), logs = Gear.count(c, s -> s.is(ItemTags.LOGS));
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < c.getContainerSize(); i++) {
				ItemStack s = c.getItem(i);
				if (s.isEmpty()) continue;
				boolean ok = pass == 0 ? s.is(ItemTags.COALS) || s.is(Items.COAL_BLOCK)
						: s.is(ItemTags.PLANKS) && planks > 12 || s.is(ItemTags.LOGS) && logs > 16 || s.is(Items.STICK) && Gear.count(c, Items.STICK) > 8;
				if (!ok) continue;
				int value = level.fuelValues().burnDuration(s);
				if (value <= 0) continue;
				s.shrink(1);
				c.setChanged();
				burn = value;
				return true;
			}
		}
		return false;
	}

	public void save(ValueOutput out) {
		out.putInt("Burn", burn);
		out.putInt("Cook", progress);
	}

	public void load(ValueInput in) {
		burn = in.getIntOr("Burn", 0);
		progress = in.getIntOr("Cook", 0);
	}
}
