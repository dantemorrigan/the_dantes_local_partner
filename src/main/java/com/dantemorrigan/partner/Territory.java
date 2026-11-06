package com.dantemorrigan.partner;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where players live and build, so the partner never digs or breaks anything there:
 * <ul>
 *   <li>every place a player put a block (remembered in 4×4×4 cells, saved with the world): 8 blocks around it;</li>
 *   <li>the home: 24 blocks around any online player's bed / respawn point;</li>
 *   <li>anything next to man-made blocks (planks, bricks, glass, doors, stairs, slabs, fences, beds, wool,
 *       containers...), which also covers houses built before the mod was installed and villages.</li>
 * </ul>
 * Results are cached per cell for half a minute.
 */
public final class Territory {
	private static final AttachmentType<Map<String, List<Long>>> MARKS = AttachmentRegistry.<Map<String, List<Long>>>builder()
			.persistent(Codec.unboundedMap(Codec.STRING, Codec.LONG.listOf())).buildAndRegister(LocalPartner.id("player_builds"));
	private static final int BUILD_RADIUS = 8, HOME_RADIUS = 24, NEAR_MANMADE = 3, MAX_MARKS = 60_000;

	private static final Map<ResourceKey<Level>, LongOpenHashSet> CELLS = new HashMap<>();
	private static final Long2LongOpenHashMap CACHE = new Long2LongOpenHashMap();
	private static @Nullable MinecraftServer server;
	private static boolean dirty;

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			CELLS.clear();
			CACHE.clear();
			s.overworld().getAttachedOrElse(MARKS, Map.of()).forEach((dim, list) -> {
				for (ServerLevel level : s.getAllLevels()) {
					if (level.dimension().identifier().toString().equals(dim)) CELLS.put(level.dimension(), new LongOpenHashSet(list));
				}
			});
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			server = null;
			CELLS.clear();
		});
		// A player using a block item on a block = building there.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level instanceof ServerLevel sl && !player.isSpectator() && player.getItemInHand(hand).getItem() instanceof BlockItem) {
				mark(sl, hit.getBlockPos());
				mark(sl, hit.getBlockPos().relative(hit.getDirection()));
			}
			return InteractionResult.PASS;
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(s -> {
			if (dirty && s.getTickCount() % 200 == 0) save(s);
		});
	}

	private static long cell(BlockPos pos) {
		return BlockPos.asLong(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
	}

	public static void mark(ServerLevel level, BlockPos pos) {
		LongOpenHashSet set = CELLS.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet());
		if (set.size() < MAX_MARKS && set.add(cell(pos))) {
			dirty = true;
			CACHE.clear();
		}
	}

	private static void save(MinecraftServer s) {
		dirty = false;
		Map<String, List<Long>> out = new HashMap<>();
		CELLS.forEach((dim, set) -> out.put(dim.identifier().toString(), new ArrayList<>(set)));
		s.overworld().setAttached(MARKS, out);
	}

	/** True when the partner must not break or dig at {@code pos}. */
	public static boolean isProtected(ServerLevel level, BlockPos pos) {
		long key = cell(pos) ^ ((long) level.dimension().hashCode() << 1);
		long now = level.getGameTime();
		long cached = CACHE.getOrDefault(key, Long.MIN_VALUE);
		if (cached != Long.MIN_VALUE && now - (cached >> 1) < 600) return (cached & 1) == 1;
		boolean result = compute(level, pos);
		if (CACHE.size() > 20_000) CACHE.clear();
		CACHE.put(key, (now << 1) | (result ? 1 : 0));
		return result;
	}

	private static boolean compute(ServerLevel level, BlockPos pos) {
		// Player builds.
		LongOpenHashSet set = CELLS.get(level.dimension());
		if (set != null && !set.isEmpty()) {
			int r = BUILD_RADIUS >> 2;
			int cx = pos.getX() >> 2, cy = pos.getY() >> 2, cz = pos.getZ() >> 2;
			for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
				if (set.contains(BlockPos.asLong(cx + dx, cy + dy, cz + dz))) return true;
			}
		}
		// Homes (beds / respawn points of the players online).
		if (server != null) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				var cfg = player.getRespawnConfig();
				if (cfg == null) continue;
				var home = cfg.respawnData().globalPos();
				if (home.dimension() == level.dimension() && home.pos().distSqr(pos) < HOME_RADIUS * HOME_RADIUS) return true;
			}
		}
		// Man-made blocks around.
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -NEAR_MANMADE; dx <= NEAR_MANMADE; dx++) for (int dy = -NEAR_MANMADE; dy <= NEAR_MANMADE; dy++) for (int dz = -NEAR_MANMADE; dz <= NEAR_MANMADE; dz++) {
			m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
			if (manMade(level, m, level.getBlockState(m))) return true;
		}
		return false;
	}

	/** Blocks that do not generate naturally on their own (outside structures): signs of someone's build. */
	public static boolean manMade(ServerLevel level, BlockPos pos, BlockState s) {
		if (s.isAir() || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.CRAFTING_TABLE)) return false; // the partner's own tools
		if (s.is(BlockTags.PLANKS) || s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(BlockTags.WALLS)
				|| s.is(BlockTags.FENCES) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS)
				|| s.is(BlockTags.BEDS) || s.is(BlockTags.WOOL) || s.is(BlockTags.WOOL_CARPETS) || s.is(BlockTags.IMPERMEABLE)
				|| s.is(BlockTags.STONE_BRICKS) || s.is(Blocks.BRICKS) || s.is(Blocks.GLASS_PANE) || s.is(Blocks.LANTERN)
				|| s.is(Blocks.BOOKSHELF) || s.is(BlockTags.ANVIL) || s.is(Blocks.FURNACE) || s.is(Blocks.SMOKER) || s.is(Blocks.BLAST_FURNACE)
				|| s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.POLISHED_ANDESITE) || s.is(Blocks.POLISHED_DIORITE) || s.is(Blocks.POLISHED_GRANITE)
				|| s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH) || s.is(BlockTags.BANNERS) || s.is(Blocks.LADDER)) return true;
		return level.getBlockEntity(pos) != null && !s.is(Blocks.SPAWNER);
	}

	/** The home of this player in this level (bed / respawn point), if any. */
	public static @Nullable BlockPos home(ServerPlayer player, ServerLevel level) {
		var cfg = player.getRespawnConfig();
		if (cfg == null) return null;
		var home = cfg.respawnData().globalPos();
		return home.dimension() == level.dimension() ? home.pos() : null;
	}

	private Territory() {}
}
