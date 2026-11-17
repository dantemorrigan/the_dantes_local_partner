package com.dantemorrigan.kingdoms.world;

import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the simulation into the world. Whenever a town's chunks are loaded, every building is brought to where the
 * simulation says it is: the plot is leveled and given streets, then blocks go up layer by layer in proportion to
 * construction progress. With a player watching nearby, construction is slow and visible; otherwise it catches up
 * at once, so arriving at a far town shows it as it has grown.
 */
public final class Constructor {
	private static final int PREP = Settlement.PLOT * Settlement.PLOT;
	private static final int CLEAR = 44;
	private static final Map<String, Blueprint> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Blueprint> e) {
			return size() > 96;
		}
	};

	/** Chimneys and stacks breathe: smoke from fires, steam from the industrial age's stacks. */
	static void ambient(ServerLevel level, Realm realm, List<ServerPlayer> players) {
		for (Settlement s : realm.settlements.values()) {
			if (!near(players, s.x, s.z, 90)) continue;
			Kingdom k = realm.kingdoms.get(s.kingdom);
			int dye = k != null ? k.dye : 7;
			for (Settlement.Building b : s.buildings) {
				if (!b.done() || b.ruined || b.y == Integer.MIN_VALUE || pending(realm, s, b)) continue;
				Blueprint bp = blueprint(b, dye);
				if (bp.emitters.isEmpty()) continue;
				Rotation rot = rotation(s, b);
				for (int[] e : bp.emitters) {
					int[] xz = rotate(e[0], e[2], rot);
					double x = b.px + xz[0] + 0.5, y = b.y + e[1], z = b.pz + xz[1] + 0.5;
					switch (e[3]) {
						case 2 -> level.sendParticles(ParticleTypes.CLOUD, x, y, z, 3, 0.2, 0.4, 0.2, 0.03);
						case 3 -> level.sendParticles(ParticleTypes.FLAME, x, y, z, 2, 0.2, 0.2, 0.2, 0.04);
						default -> level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 1, 0.12, 0.3, 0.12, 0.015);
					}
				}
			}
		}
	}

	public static void tick(ServerLevel level, Realm realm) {
		int budget = 600;
		List<ServerPlayer> players = level.players();
		if (level.getGameTime() % 6 == 0) ambient(level, realm, players);
		for (Settlement s : realm.settlements.values()) {
			if (budget <= 0) break;
			if (!near(players, s.x, s.z, 260)) continue;
			Kingdom k = realm.kingdoms.get(s.kingdom);
			int dye = k != null ? k.dye : 7;
			for (Settlement.Building b : s.buildings) {
				if (budget <= 0) break;
				budget -= step(level, realm, s, b, dye, players, budget, false);
			}
		}
	}

	static boolean near(List<ServerPlayer> players, int x, int z, int dist) {
		for (ServerPlayer p : players) if (Math.abs(p.getX() - x) < dist && Math.abs(p.getZ() - z) < dist) return true;
		return false;
	}

	static Blueprint blueprint(Settlement.Building b, int dye) {
		String key = b.type.ordinal() + ":" + b.style + ":" + dye + ":" + b.px + ":" + b.pz;
		return CACHE.computeIfAbsent(key, q -> Structures.build(b.type, b.style, dye, b.px * 31L ^ b.pz * 17L));
	}

	static Rotation rotation(Settlement s, Settlement.Building b) {
		int dx = s.x - (b.px + Settlement.PLOT / 2), dz = s.z - (b.pz + Settlement.PLOT / 2);
		if (Math.abs(dx) < 3 && Math.abs(dz) < 3) return Rotation.NONE;
		if (Math.abs(dz) >= Math.abs(dx)) return dz > 0 ? Rotation.CLOCKWISE_180 : Rotation.NONE;
		return dx > 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
	}

	static int[] rotate(int x, int z, Rotation r) {
		int m = Settlement.PLOT - 1;
		return switch (r) {
			case CLOCKWISE_90 -> new int[]{m - z, x};
			case CLOCKWISE_180 -> new int[]{m - x, m - z};
			case COUNTERCLOCKWISE_90 -> new int[]{z, m - x};
			default -> new int[]{x, z};
		};
	}

	static boolean loaded(ServerLevel level, Settlement.Building b) {
		int x1 = b.px >> 4, z1 = b.pz >> 4, x2 = (b.px + Settlement.PLOT) >> 4, z2 = (b.pz + Settlement.PLOT) >> 4;
		for (int cx = x1; cx <= x2; cx++) for (int cz = z1; cz <= z2; cz++) if (!level.getChunkSource().hasChunk(cx, cz)) return false;
		return true;
	}

	/** Builders of this town standing at the building's plot. */
	static int crew(Settlement s, Settlement.Building b) {
		int n = 0;
		double cx = b.px + 6, cz = b.pz + 6;
		for (CitizenEntity c : Population.of(s.id)) {
			if (c.role() == CitizenEntity.BUILDER && c.isAlive() && Math.abs(c.getX() - cx) < 22 && Math.abs(c.getZ() - cz) < 22) n++;
		}
		return n;
	}

	/** Blocks of a building the simulation says exist (everything, for a building that stands; the finished share of a new one). */
	public static boolean pending(Realm realm, Settlement s, Settlement.Building b) {
		if (b.ruined) return false;
		if (!b.done()) return true;
		Kingdom k = realm.kingdoms.get(s.kingdom);
		return b.placed < PREP + blueprint(b, k == null ? 7 : k.dye).size();
	}

	/**
	 * Places blocks for one building; returns how many it placed. A building that stands in the simulation appears at
	 * once when its chunks are there (the world was lived in before anyone looked). Only the last stretch of a building
	 * that is still going up is built in front of the player's eyes, and only where builders are working.
	 */
	static int step(ServerLevel level, Realm realm, Settlement s, Settlement.Building b, int dye, List<ServerPlayer> players, int budget, boolean force) {
		Blueprint bp = blueprint(b, dye);
		int total = PREP + bp.size();
		int target = b.ruined ? b.placed : PREP + (int) Math.floor(b.progress * bp.size());
		boolean flagsStale = b.done() && b.placed >= total && b.flagDye != dye && !b.ruined;
		if (b.placed >= target && !flagsStale) return 0;
		if (!loaded(level, b)) return 0;
		if (b.y == Integer.MIN_VALUE) b.y = ground(level, b);
		boolean zone = !force && near(players, b.px + 6, b.pz + 6, 80);
		boolean fresh = !b.done() || realm.day - b.doneDay <= 1;
		boolean live = zone && fresh;
		int window = Math.max(60, (int) (1.5f / b.type.days * bp.size()));
		int instantTo = live ? target - window : target;
		int crew = live ? crew(s, b) : 0;
		Rotation rot = rotation(s, b);
		int done = 0, gated = 0;
		int cap = force ? Integer.MAX_VALUE : budget;
		while (b.placed < target && done < cap) {
			boolean needsCrew = live && b.placed >= instantTo;
			if (needsCrew && (crew == 0 || gated >= crew * 3)) break;
			if (b.placed < PREP) {
				prepColumn(level, realm, b, b.placed);
				done += 4;
			} else {
				Blueprint.Entry e = bp.entries().get(b.placed - PREP);
				place(level, b, e, rot, needsCrew);
				done++;
			}
			if (needsCrew) gated++;
			b.placed++;
			if (!force && !needsCrew && done >= 400) break;
		}
		if (b.placed >= total && b.done()) {
			if (b.flagDye != dye) {
				for (Blueprint.Entry e : bp.entries()) if (e.flag()) place(level, b, e, rot, false);
				b.flagDye = dye;
			}
		}
		return done;
	}

	/**
	 * Everything the simulation says stands within {@code radius} blocks of (x, z) is put into the world right now,
	 * loading the chunks (the player is about to arrive there). The kingdom must look lived in, not under construction.
	 */
	public static void flushAround(ServerLevel level, Realm realm, int x, int z, int radius) {
		int r = radius + 40;
		for (int cx = (x - r) >> 4; cx <= (x + r) >> 4; cx++) for (int cz = (z - r) >> 4; cz <= (z + r) >> 4; cz++) level.getChunk(cx, cz);
		for (Settlement s : realm.settlements.values()) {
			if (Math.abs(s.x - x) > r || Math.abs(s.z - z) > r) continue;
			Kingdom k = realm.kingdoms.get(s.kingdom);
			int dye = k != null ? k.dye : 7;
			for (Settlement.Building b : s.buildings) step(level, realm, s, b, dye, List.of(), 0, true);
		}
		WallWorks.flush(level, realm, x, z, r);
		RoadWorks.flush(level, realm, x, z, r);
		for (Settlement s : realm.settlements.values()) {
			if (Math.abs(s.x - x) <= r && Math.abs(s.z - z) <= r) Population.populate(level, realm, s);
		}
	}

	static void place(ServerLevel level, Settlement.Building b, Blueprint.Entry e, Rotation rot, boolean watched) {
		int[] xz = rotate(e.x(), e.z(), rot);
		BlockPos pos = new BlockPos(b.px + xz[0], b.y + e.y(), b.pz + xz[1]);
		BlockState state = e.state().rotate(rot);
		level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		if (watched && !state.isAir() && level.getRandom().nextInt(3) == 0) {
			level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.6f, 0.9f + level.getRandom().nextFloat() * 0.2f);
			level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 2, 0.3, 0.3, 0.3, 0.01);
		}
	}

	/** Level one column of the plot: clear above, fill below, grass inside and a street around. */
	static void prepColumn(ServerLevel level, Realm realm, Settlement.Building b, int i) {
		int x = i % Settlement.PLOT, z = i / Settlement.PLOT;
		int wx = b.px + x, wz = b.pz + z;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(wx, b.y, wz);
		BlockState was = level.getBlockState(pos);
		for (int y = 1; y <= CLEAR; y++) {
			pos.setY(b.y + y);
			if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		for (int y = -1; y >= -8; y--) {
			pos.setY(b.y + y);
			BlockState st = level.getBlockState(pos);
			if (!(st.isAir() || st.canBeReplaced() || st.is(BlockTags.LEAVES) || !st.getFluidState().isEmpty())) break;
			level.setBlock(pos, y < -2 ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		pos.setY(b.y);
		boolean street = x == 0 || z == 0 || x == Settlement.PLOT - 1 || z == Settlement.PLOT - 1;
		BlockState top;
		if (street) top = Structures.palette(b.style).road();
		else if (was.is(Blocks.SAND) || was.is(Blocks.RED_SAND) || was.is(Blocks.SANDSTONE)) top = Blocks.SAND.defaultBlockState();
		else if (was.is(Blocks.SNOW_BLOCK)) top = Blocks.SNOW_BLOCK.defaultBlockState();
		else top = Blocks.GRASS_BLOCK.defaultBlockState();
		if (b.type == com.dantemorrigan.kingdoms.sim.BuildingType.QUARRY && !street) top = Blocks.STONE.defaultBlockState();
		level.setBlock(pos, top, Block.UPDATE_CLIENTS);
		if (street && (x + z) % 11 == 0 && b.style > 0 && x % 11 == 0 && z % 11 == 0) {
			// lamp posts at the street corners
			pos.setY(b.y + 1);
			level.setBlock(pos, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
			pos.setY(b.y + 2);
			level.setBlock(pos, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	/** The plot's floor level: the median of the ground at the center and around it. */
	static int ground(ServerLevel level, Settlement.Building b) {
		int[] xs = {6, 2, 9, 2, 9}, zs = {6, 2, 2, 9, 9};
		int[] hs = new int[xs.length];
		for (int i = 0; i < xs.length; i++) hs[i] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, b.px + xs[i], b.pz + zs[i]) - 1;
		java.util.Arrays.sort(hs);
		return Math.max(level.getMinY() + 10, hs[hs.length / 2]);
	}

	/** Builder sound/visual for god-mode effects: knock down part of a building (meteor, war damage). */
	public static void ruin(ServerLevel level, Settlement.Building b) {
		if (b.y == Integer.MIN_VALUE || !loaded(level, b)) return;
		BlockPos c = new BlockPos(b.px + 6, b.y + 2, b.pz + 6);
		level.playSound(null, c, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 2f, 0.8f);
		for (int i = 0; i < 70; i++) {
			BlockPos p = c.offset(level.getRandom().nextInt(11) - 5, level.getRandom().nextInt(8) - 1, level.getRandom().nextInt(11) - 5);
			if (!level.getBlockState(p).isAir()) level.setBlock(p, level.getRandom().nextInt(5) == 0 ? Blocks.FIRE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.getX(), c.getY(), c.getZ(), 1, 0, 0, 0, 0);
	}

	private Constructor() {}
}
