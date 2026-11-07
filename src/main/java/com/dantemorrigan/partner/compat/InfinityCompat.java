package com.dantemorrigan.partner.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Compatibility with The Dante's Infinity (mod id {@code infinity_gauntlet}) without linking against it:
 * everything goes through registry ids, so the partner works the same with or without the mod.
 *
 * <ul>
 *   <li>The partner never picks up, carries or uses the mod's items (stones, gauntlet, extractor, quest items):
 *       the stones stay with players, so Thanos, the stone keeper and the quests keep working as designed.</li>
 *   <li>Scripted dimensions (Dead Space, the White Void after the Snap, the Soul World ritual, open space)
 *       are personal story moments: the partner waits "off-screen" and comes back when the owner returns.</li>
 *   <li>The partner is an owned, named entity, which the mod already spares (Reality Strike, Soul extraction).</li>
 *   <li>Thanos is treated as the top-priority enemy; Uru ore is mined only with a diamond pickaxe or better.</li>
 * </ul>
 */
public final class InfinityCompat {
	public static final String MOD_ID = "infinity_gauntlet";
	public static boolean LOADED;

	private static final Set<String> WAIT_DIMENSIONS = Set.of("dead_space", "white_void", "soul_world", "cosmos");
	private static final Identifier THANOS = Identifier.fromNamespaceAndPath(MOD_ID, "thanos");
	private static final Identifier MIND_ZOMBIE = Identifier.fromNamespaceAndPath(MOD_ID, "mind_zombie");

	public static void init() {
		LOADED = FabricLoader.getInstance().isModLoaded(MOD_ID);
		if (LOADED) com.dantemorrigan.partner.ai.Crafting.initModRecipes();
	}

	/**
	 * Items of the mod the partner never picks up or hands around: stones, gauntlet, extractor, maps, gears...
	 * Uru materials and Uru armor are ordinary loot, so those are allowed (the partner may even wear Uru armor).
	 */
	public static boolean isProtectedItem(ItemStack stack) {
		if (stack.isEmpty()) return false;
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return MOD_ID.equals(id.getNamespace()) && !id.getPath().contains("uru");
	}

	/** Dimensions the partner does not enter: it waits and reappears when the owner is back. */
	public static boolean partnerWaitsOutside(ResourceKey<Level> dimension) {
		Identifier id = dimension.identifier();
		return MOD_ID.equals(id.getNamespace()) && WAIT_DIMENSIONS.contains(id.getPath());
	}

	public static boolean isBoss(Entity e) {
		return THANOS.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
	}

	/** Mod hostiles the partner should always fight (they are also regular Monsters). */
	public static boolean isModHostile(Entity e) {
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
		return THANOS.equals(id) || MIND_ZOMBIE.equals(id);
	}

	public static boolean isUruOre(BlockState state) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		return MOD_ID.equals(id.getNamespace()) && id.getPath().endsWith("uru_ore");
	}

	public static boolean isUruMaterial(ItemStack stack) {
		if (stack.isEmpty()) return false;
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		if (!MOD_ID.equals(id.getNamespace())) return false;
		String p = id.getPath();
		return p.equals("uru_ingot") || p.equals("raw_uru") || p.endsWith("uru_ore") || p.equals("uru");
	}

	public static boolean isUruIngot(ItemStack stack) {
		if (stack.isEmpty()) return false;
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return MOD_ID.equals(id.getNamespace()) && (id.getPath().equals("uru_ingot") || id.getPath().equals("uru"));
	}

	public static net.minecraft.world.item.Item item(String path) {
		return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(MOD_ID, path));
	}

	private InfinityCompat() {}
}
