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
		DAMAGE.clear();
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
		if (level.getGameTime() % 100 == 0) repair(realm);
		List<ServerPlayer> players = level.players();
		int budget = 400;
		for (Settlement s : realm.settlements.values()) {
			if (budget <= 0) break;
			if (s.wall == 0 || s.wallProgress <= 0 || !Constructor.near(players, s.x, s.z, 260)) continue;
			int style = s.wall - 1;
			int target = (int) (s.wallProgress * s.wallLen());
			boolean fresh = s.wallProgress < 1f || realm.day - s.wallDoneDay <= 1;
			int cur = CURSOR.getOrDefault(s.id, 0);
			int scanned = 0, placed = 0;
			int len = s.wallLen();
			while (scanned < len && placed < 24 && budget > 0) {
				int i = (cur + scanned) % len;
				scanned++;
				if (i >= target || s.wallBit(i)) continue;
				int[] pos = s.wallPos(i);
				if (!level.getChunkSource().hasChunk(pos[0] >> 4, pos[1] >> 4)) continue;
				boolean live = fresh && i >= target - 24 && Constructor.near(players, pos[0], pos[1], 80);
				if (live && !crewNear(s, pos[0], pos[1])) continue;
				column(level, s, style, i, live);
				s.setWallBit(i);
				placed++;
				budget -= 10;
			}
			CURSOR.put(s.id, (cur + scanned) % s.wallLen());
		}
	}

	/** Everything the simulation says stands is put in the world now (the player is arriving). */
	public static void flush(ServerLevel level, Realm realm, int x, int z, int radius) {
		for (Settlement s : realm.settlements.values()) {
			if (s.wall == 0 || Math.abs(s.x - x) > radius + 60 || Math.abs(s.z - z) > radius + 60) continue;
			int style = s.wall - 1;
			int target = (int) (s.wallProgress * s.wallLen());
			for (int i = 0; i < target; i++) {
				if (s.wallBit(i)) continue;
				int[] pos = s.wallPos(i);
				if (!level.getChunkSource().hasChunk(pos[0] >> 4, pos[1] >> 4)) continue;
				column(level, s, style, i, false);
				s.setWallBit(i);
			}
		}
	}

	/** The ground in a column: {surface, bed}. Over water the surface is the water level and the bed is the floor below it. */
	static int[] surface(ServerLevel level, int x, int z) {
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
		BlockState first = level.getBlockState(p);
		if (!first.getFluidState().isEmpty()) {
			int water = top;
			for (int d = 0; d < 16; d++) {
				p.setY(top - d);
				BlockState st = level.getBlockState(p);
				if (st.getFluidState().isEmpty() && !st.isAir()) return new int[]{water, top - d};
			}
			return new int[]{water, top - 16};
		}
		for (int i = 0; i < 24; i++) {
			BlockState st = level.getBlockState(p);
			if (!(st.isAir() || st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.canBeReplaced() || st.is(BlockTags.FLOWERS))) return new int[]{p.getY(), p.getY()};
			p.move(0, -1, 0);
		}
		return null;
	}

	/** One stretch of the wall: from the foundation up, with a walkway behind the parapet from the first age of stone on. */
	static void column(ServerLevel level, Settlement s, int style, int i, boolean watched) {
		if (s.gate(i)) return; // the gate house stands here
		int rows = style == 0 ? 1 : 2;
		for (int off = 0; off < rows; off++) {
			int[] pos = s.wallPos(i, off);
			stack(level, style, i, pos[0], pos[1], off, watched && off == 0);
		}
	}

	static void stack(ServerLevel level, int style, int i, int x, int z, int off, boolean watched) {
		int[] sf = surface(level, x, z);
		if (sf == null) return;
		int ground = sf[0], bed = sf[1];
		int h = height(style);
		BlockState body = switch (style) {
			case 0 -> Blocks.SPRUCE_LOG.defaultBlockState();
			case 1 -> Blocks.COBBLESTONE.defaultBlockState();
			case 2, 3 -> Blocks.STONE_BRICKS.defaultBlockState();
			default -> Blocks.POLISHED_ANDESITE.defaultBlockState();
		};
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, 0, z);
		// foundation: down to the bed, so there is never a gap under the wall
		for (int y = Math.min(bed, ground) - 3; y <= ground; y++) {
			p.setY(y);
			BlockState st = level.getBlockState(p);
			if (st.isAir() || st.canBeReplaced() || st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || !st.getFluidState().isEmpty()) {
				level.setBlock(p, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
		int solid = style == 0 ? h - 1 : off == 0 ? h : h - 1;
		for (int dy = 1; dy <= solid; dy++) {
			p.setY(ground + dy);
			BlockState st = body;
			if (style == 4 && off == 0 && dy == 4 && i % 9 == 0) st = Blocks.IRON_BARS.defaultBlockState();
			if (style == 3 && off == 0 && dy == h - 1) st = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
			level.setBlock(p, st, Block.UPDATE_CLIENTS);
		}
		if (off == 0) { // the parapet on the outer face
			p.setY(ground + solid + 1);
			BlockState crown = switch (style) {
				case 0 -> Blocks.OAK_FENCE.defaultBlockState();
				case 1 -> i % 2 == 0 ? Blocks.COBBLESTONE_WALL.defaultBlockState() : Blocks.AIR.defaultBlockState();
				default -> i % 2 == 0 ? Blocks.STONE_BRICK_WALL.defaultBlockState() : Blocks.AIR.defaultBlockState();
			};
			level.setBlock(p, crown, Block.UPDATE_ALL);
		} else if (i % 14 == 7) { // torches on the walkway
			p.setY(ground + solid + 1);
			level.setBlock(p, style >= 3 ? Blocks.LANTERN.defaultBlockState() : Blocks.TORCH.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		for (int dy = solid + 2; dy <= solid + 4; dy++) { // free the air above from leaves and branches
			p.setY(ground + dy);
			if (level.getBlockState(p).is(BlockTags.LEAVES)) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		if (watched && level.getRandom().nextInt(3) == 0) {
			level.playSound(null, new BlockPos(x, ground + 3, z), body.getSoundType().getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS, 0.7f, 0.9f);
		}
	}

	/** Breaches in the wall: the columns are put back a few days after the fighting. {settlement, column, day}. */
	private static final java.util.List<long[]> DAMAGE = new java.util.ArrayList<>();

	public static void damaged(Settlement s, int col, long day) {
		for (int k = -5; k <= 5; k++) DAMAGE.add(new long[]{s.id, (col + k + s.wallLen()) % s.wallLen(), day + 4});
	}

	static void repair(Realm realm) {
		DAMAGE.removeIf(e -> {
			if (e[2] > realm.day) return false;
			Settlement s = realm.settlements.get((int) e[0]);
			if (s != null) s.wallBits[(int) e[1] >> 6] &= ~(1L << ((int) e[1] & 63));
			return true;
		});
	}

	private WallWorks() {}
}
