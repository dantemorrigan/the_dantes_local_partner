package com.dantemorrigan.kingdoms.sim;

import com.dantemorrigan.kingdoms.KingdomsMod;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Locale;

/**
 * The land of the realm as the map shows it: a terrain class and a surface height for every 8x8 blocks. Sampled
 * straight from the world generator (biome noise and base height) on a worker thread, so the whole map is known
 * from the first minute without generating a single chunk.
 */
public final class Terrain {
	public static final int SAMPLE = 8;

	public static final byte DEEP_OCEAN = 0, OCEAN = 1, RIVER = 2, BEACH = 3, PLAINS = 4, FOREST = 5, DESERT = 6, SNOW = 7,
			MOUNTAIN = 8, SWAMP = 9, JUNGLE = 10, SAVANNA = 11, BADLANDS = 12, TAIGA = 13, FLOWER = 14, CHERRY = 15;

	public static boolean water(int cls) {
		return cls == DEEP_OCEAN || cls == OCEAN || cls == RIVER;
	}

	/** Samples per side. */
	public final int size;
	public final byte[] cls;
	public final byte[] height;
	public volatile boolean ready;
	public volatile int rowsDone;

	public Terrain(int size) {
		this.size = size;
		this.cls = new byte[size * size];
		this.height = new byte[size * size];
	}

	public int heightAt(int sx, int sz) {
		return height[sz * size + sx] & 0xFF;
	}

	/** Heights are sampled every {@value #STEP} samples (generator height is the slow part) and interpolated. */
	private static final int STEP = 4;

	/** Generate on worker threads; minX/minZ are the block coordinates of sample (0,0). */
	public void generate(ServerLevel level, int minX, int minZ) {
		Thread t = new Thread(() -> {
			long start = System.currentTimeMillis();
			try {
				ChunkGenerator gen = level.getChunkSource().getGenerator();
				RandomState rs = level.getChunkSource().randomState();
				BiomeSource biomes = gen.getBiomeSource();
				Climate.Sampler sampler = rs.sampler();
				int sea = gen.getSeaLevel();
				int g = (size + STEP - 1) / STEP + 1;
				int[] coarse = new int[g * g];
				java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1)));
				pool.submit(() -> java.util.stream.IntStream.range(0, g).parallel().forEach(gz -> {
					for (int gx = 0; gx < g; gx++) {
						int bx = minX + gx * STEP * SAMPLE + SAMPLE / 2, bz = minZ + gz * STEP * SAMPLE + SAMPLE / 2;
						int h;
						try {
							h = gen.getBaseHeight(bx, bz, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
						} catch (Throwable e) {
							h = sea;
						}
						coarse[gz * g + gx] = h;
					}
				})).get();
				pool.shutdown();
				for (int z = 0; z < size; z++) {
					for (int x = 0; x < size; x++) {
						int gx = x / STEP, gz = z / STEP;
						float fx = (x % STEP) / (float) STEP, fz = (z % STEP) / (float) STEP;
						float h0 = coarse[gz * g + gx] * (1 - fx) + coarse[gz * g + gx + 1] * fx;
						float h1 = coarse[(gz + 1) * g + gx] * (1 - fx) + coarse[(gz + 1) * g + gx + 1] * fx;
						int h = Math.round(h0 * (1 - fz) + h1 * fz);
						int bx = minX + x * SAMPLE + SAMPLE / 2, bz = minZ + z * SAMPLE + SAMPLE / 2;
						Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(Math.max(h, sea)), QuartPos.fromBlock(bz), sampler);
						cls[z * size + x] = classify(biome, h, sea);
						height[z * size + x] = (byte) Math.max(0, Math.min(255, h));
					}
					rowsDone = z + 1;
				}
			} catch (Throwable e) {
				KingdomsMod.LOG.error("Terrain sampling failed", e);
			}
			ready = true;
			KingdomsMod.LOG.info("Realm terrain {}x{} sampled in {} ms", size, size, System.currentTimeMillis() - start);
		}, "Kingdoms terrain");
		t.setDaemon(true);
		t.start();
	}

	static byte classify(Holder<Biome> biome, int h, int sea) {
		String id = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("plains").toLowerCase(Locale.ROOT);
		if (id.contains("deep") && id.contains("ocean")) return DEEP_OCEAN;
		if (id.contains("ocean")) return OCEAN;
		if (id.contains("river")) return RIVER;
		if (h < sea - 4) return OCEAN;
		if (id.contains("beach") || id.contains("shore")) return BEACH;
		if (id.contains("peak") || id.contains("stony") || id.contains("windswept") || id.contains("slopes") || h > sea + 70) return MOUNTAIN;
		if (id.contains("badlands")) return BADLANDS;
		if (id.contains("desert")) return DESERT;
		if (id.contains("snow") || id.contains("frozen") || id.contains("ice") || id.contains("grove")) return SNOW;
		if (id.contains("swamp") || id.contains("mangrove")) return SWAMP;
		if (id.contains("jungle") || id.contains("bamboo")) return JUNGLE;
		if (id.contains("savanna")) return SAVANNA;
		if (id.contains("taiga")) return TAIGA;
		if (id.contains("cherry")) return CHERRY;
		if (id.contains("flower") || id.contains("meadow")) return FLOWER;
		if (id.contains("forest") || id.contains("wood")) return FOREST;
		return PLAINS;
	}
}
