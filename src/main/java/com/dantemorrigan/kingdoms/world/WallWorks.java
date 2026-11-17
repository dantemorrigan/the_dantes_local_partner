package com.dantemorrigan.kingdoms.world;

import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Town walls in the world: a square ring around the town, laid column by column. A palisade of logs in the first age,
 * then stone, stone brick with battlements, and finally smooth stone with iron-barred windows. Gates are left open at
 * the middle of every side. Like everything else, the part that exists in the simulation is just there when the chunks
 * load; only the last stretch of a wall still going up is built in front of the player, where builders stand.
 */
public final class WallWorks {
	private static final Map<Integer, Integer> CURSOR = new HashMap<>();

	public static void clear() {
		CURSOR.clear();
	}

	static int height(int style) {
		return switch (style) {
			case 0, 1 -> 5;
			case 2 -> 6;
			case 3 -> 7;
			default -> 8;
		};
	}

	/** y of the topmost solid ground block in the column (trees and plants skipped), or MIN_VALUE over water. */
	static int groundY(ServerLevel level, int x, int z) {
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
		for (int i = 0; i < 24; i++) {
			BlockState st = level.getBlockState(p);
			if (!st.getFluidState().isEmpty()) return Integer.MIN_VALUE;
			if (!(st.isAir() || st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.canBeReplaced() || st.is(BlockTags.FLOWERS))) return p.getY();
			p.move(0, -1, 0);
		}
		return Integer.MIN_VALUE;
	}

	static boolean crewNear(Settlement s, int x, int z) {
		for (CitizenEntity c : Population.of(s.id)) {
			if (c.role() == CitizenEntity.BUILDER && c.isAlive() && Math.abs(c.getX() - x) < 24 && Math.abs(c.getZ() - z) < 24) return true;
		}
		return false;
	}

	public static void tick(ServerLevel level, Realm realm) {
		List<ServerPlayer> players = level.players();
		int budget = 400;
		for (Settlement s : realm.settlements.values()) {
			if (budget <= 0) break;
			if (s.wall == 0 || s.wallProgress <= 0 || !Constructor.near(players, s.x, s.z, 260)) continue;
			int style = s.wall - 1;
			int target = (int) (s.wallProgress * Settlement.WALL_LEN);
			boolean fresh = s.wallProgress < 1f || realm.day - s.wallDoneDay <= 1;
			int cur = CURSOR.getOrDefault(s.id, 0);
			int scanned = 0, placed = 0;
			while (scanned < Settlement.WALL_LEN && placed < 24 && budget > 0) {
				int i = (cur + scanned) % Settlement.WALL_LEN;
				scanned++;
				if (i >= target || s.wallBit(i)) continue;
				int[] pos = s.wallPos(i);
				if (!level.getChunkSource().hasChunk(pos[0] >> 4, pos[1] >> 4)) continue;
				boolean live = fresh && i >= target - 24 && Constructor.near(players, pos[0], pos[1], 80);
				if (live && !crewNear(s, pos[0], pos[1])) continue;
				column(level, style, i, pos[0], pos[1], live);
				s.setWallBit(i);
				placed++;
				budget -= 10;
			}
			CURSOR.put(s.id, (cur + scanned) % Settlement.WALL_LEN);
		}
	}

	/** Everything the simulation says stands is put in the world now (the player is arriving). */
	public static void flush(ServerLevel level, Realm realm, int x, int z, int radius) {
		for (Settlement s : realm.settlements.values()) {
			if (s.wall == 0 || Math.abs(s.x - x) > radius + 60 || Math.abs(s.z - z) > radius + 60) continue;
			int style = s.wall - 1;
			int target = (int) (s.wallProgress * Settlement.WALL_LEN);
			for (int i = 0; i < target; i++) {
				if (s.wallBit(i)) continue;
				int[] pos = s.wallPos(i);
				if (!level.getChunkSource().hasChunk(pos[0] >> 4, pos[1] >> 4)) continue;
				column(level, style, i, pos[0], pos[1], false);
				s.setWallBit(i);
			}
		}
	}

	static void column(ServerLevel level, int style, int i, int x, int z, boolean watched) {
		if (Settlement.gate(i) || RoadWorks.isRoad(x, z)) return;
		int gy = groundY(level, x, z);
		if (gy == Integer.MIN_VALUE) return;
		int h = height(style);
		BlockState body = switch (style) {
			case 0 -> Blocks.SPRUCE_LOG.defaultBlockState();
			case 1 -> Blocks.COBBLESTONE.defaultBlockState();
			case 2, 3 -> Blocks.STONE_BRICKS.defaultBlockState();
			default -> Blocks.POLISHED_ANDESITE.defaultBlockState();
		};
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, gy, z);
		for (int dy = -1; dy >= -4; dy--) {
			p.setY(gy + dy);
			BlockState st = level.getBlockState(p);
			if (!(st.isAir() || st.canBeReplaced() || st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS))) break;
			level.setBlock(p, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		for (int dy = 1; dy <= h; dy++) {
			p.setY(gy + dy);
			BlockState st = body;
			if (style == 4 && dy == 4 && i % 9 == 0) st = Blocks.IRON_BARS.defaultBlockState();
			if (style == 3 && dy == h - 1) st = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
			level.setBlock(p, st, Block.UPDATE_CLIENTS);
		}
		p.setY(gy + h + 1);
		BlockState crown = switch (style) {
			case 0 -> Blocks.OAK_FENCE.defaultBlockState();
			case 1 -> i % 2 == 0 ? Blocks.COBBLESTONE_WALL.defaultBlockState() : Blocks.AIR.defaultBlockState();
			default -> i % 2 == 0 ? Blocks.STONE_BRICK_WALL.defaultBlockState() : Blocks.AIR.defaultBlockState();
		};
		level.setBlock(p, crown, Block.UPDATE_ALL);
		for (int dy = h + 2; dy <= h + 3; dy++) {
			p.setY(gy + dy);
			if (level.getBlockState(p).is(BlockTags.LEAVES)) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		if (style >= 1 && i % 26 == 13) { // torches along the wall top
			p.setY(gy + h + 2);
			level.setBlock(p, style >= 3 ? Blocks.LANTERN.defaultBlockState() : Blocks.TORCH.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		if (watched && level.getRandom().nextInt(3) == 0) {
			level.playSound(null, new BlockPos(x, gy + 3, z), body.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 0.7f, 0.9f);
		}
	}

	private WallWorks() {}
}
