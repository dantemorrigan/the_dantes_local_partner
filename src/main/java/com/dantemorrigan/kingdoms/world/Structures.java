package com.dantemorrigan.kingdoms.world;

import com.dantemorrigan.kingdoms.sim.BuildingType;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Procedural architecture. Every building type has a design, and every era its own materials: log huts and hay
 * roofs, timber and plank, stone castles, white-walled Renaissance towns with tiled roofs, brick factories with
 * smoking chimneys. Front doors face north (low z); the constructor turns each plot toward its town center.
 */
public final class Structures {
	public static final Block[] WOOL = {Blocks.WHITE_WOOL, Blocks.ORANGE_WOOL, Blocks.MAGENTA_WOOL, Blocks.LIGHT_BLUE_WOOL,
			Blocks.YELLOW_WOOL, Blocks.LIME_WOOL, Blocks.PINK_WOOL, Blocks.GRAY_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.CYAN_WOOL,
			Blocks.PURPLE_WOOL, Blocks.BLUE_WOOL, Blocks.BROWN_WOOL, Blocks.GREEN_WOOL, Blocks.RED_WOOL, Blocks.BLACK_WOOL};
	public static final Block[] CARPET = {Blocks.WHITE_CARPET, Blocks.ORANGE_CARPET, Blocks.MAGENTA_CARPET, Blocks.LIGHT_BLUE_CARPET,
			Blocks.YELLOW_CARPET, Blocks.LIME_CARPET, Blocks.PINK_CARPET, Blocks.GRAY_CARPET, Blocks.LIGHT_GRAY_CARPET, Blocks.CYAN_CARPET,
			Blocks.PURPLE_CARPET, Blocks.BLUE_CARPET, Blocks.BROWN_CARPET, Blocks.GREEN_CARPET, Blocks.RED_CARPET, Blocks.BLACK_CARPET};

	/** Materials of one era. */
	public record Palette(BlockState wall, BlockState frame, BlockState floor, BlockState found, BlockState roof, BlockState roofTop,
						  BlockState window, BlockState road, BlockState light, BlockState fortress, Block door) {}

	public static Palette palette(int era) {
		return switch (era) {
			case 0 -> new Palette(s(Blocks.STRIPPED_OAK_LOG), s(Blocks.OAK_LOG), s(Blocks.COARSE_DIRT), s(Blocks.COBBLESTONE),
					s(Blocks.OAK_STAIRS), slab(Blocks.OAK_SLAB), s(Blocks.AIR), s(Blocks.DIRT_PATH), s(Blocks.TORCH), s(Blocks.OAK_LOG), Blocks.OAK_DOOR);
			case 1 -> new Palette(s(Blocks.OAK_PLANKS), s(Blocks.OAK_LOG), s(Blocks.SPRUCE_PLANKS), s(Blocks.COBBLESTONE),
					s(Blocks.SPRUCE_STAIRS), slab(Blocks.SPRUCE_SLAB), s(Blocks.GLASS_PANE), s(Blocks.DIRT_PATH), s(Blocks.LANTERN),
					s(Blocks.COBBLESTONE), Blocks.SPRUCE_DOOR);
			case 2 -> new Palette(s(Blocks.STONE_BRICKS), s(Blocks.SPRUCE_LOG), s(Blocks.SPRUCE_PLANKS), s(Blocks.COBBLESTONE),
					s(Blocks.DARK_OAK_STAIRS), slab(Blocks.DARK_OAK_SLAB), s(Blocks.GLASS_PANE), s(Blocks.GRAVEL), s(Blocks.LANTERN),
					s(Blocks.STONE_BRICKS), Blocks.DARK_OAK_DOOR);
			case 3 -> new Palette(s(Blocks.WHITE_TERRACOTTA), s(Blocks.STRIPPED_DARK_OAK_LOG), s(Blocks.POLISHED_ANDESITE), s(Blocks.STONE_BRICKS),
					s(Blocks.BRICK_STAIRS), slab(Blocks.BRICK_SLAB), s(Blocks.GLASS_PANE), s(Blocks.POLISHED_ANDESITE),
					s(Blocks.LANTERN), s(Blocks.STONE_BRICKS), Blocks.SPRUCE_DOOR);
			default -> new Palette(s(Blocks.BRICKS), s(Blocks.STONE_BRICKS), s(Blocks.SMOOTH_STONE), s(Blocks.SMOOTH_STONE),
					s(Blocks.DEEPSLATE_TILE_STAIRS), slab(Blocks.DEEPSLATE_TILE_SLAB), s(Blocks.GLASS_PANE), s(Blocks.ANDESITE),
					s(Blocks.LANTERN), s(Blocks.POLISHED_ANDESITE), Blocks.IRON_DOOR);
		};
	}

	private static BlockState s(Block b) {
		return b.defaultBlockState();
	}

	private static BlockState slab(Block b) {
		return b.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
	}

	public static Blueprint build(BuildingType type, int era, int dye, long seed) {
		Palette p = palette(era);
		BlockState cloth = WOOL[Math.floorMod(dye, 16)].defaultBlockState();
		RandomSource r = RandomSource.create(seed);
		Blueprint b = new Blueprint();
		b.gableWall = p.wall;
		switch (type) {
			case KEEP -> Architecture.keep(b, p, era, cloth, dye);
			case TOWN_HALL -> Architecture.townHall(b, p, era, cloth);
			case HOUSE -> Architecture.house(b, p, era, r);
			case FARM -> farm(b, era, r);
			case LUMBER_CAMP -> lumber(b, p);
			case QUARRY -> quarry(b, era);
			case MINE -> mine(b, p);
			case BARRACKS -> Architecture.barracks(b, p, era, cloth);
			case SMITHY -> Architecture.smithy(b, p, era);
			case MARKET -> market(b, p, cloth);
			case TEMPLE -> Architecture.temple(b, p, era);
			case LIBRARY -> Architecture.library(b, p, era);
			case WALL_TOWER -> Architecture.tower(b, p, era, cloth);
			case WORKSHOP -> Architecture.workshop(b, p, era);
			case FACTORY -> Architecture.factory(b, p, era);
			case WINDMILL -> Architecture.windmill(b, p, era);
			case SAWMILL -> Architecture.sawmill(b, p, era);
			case FOUNDRY -> Architecture.foundry(b, p, era);
			case UNIVERSITY -> Architecture.university(b, p, era);
			case STATION -> Architecture.station(b, p, era);
		}
		return b;
	}

	// ---------------------------------------------------------------- designs

	static void farm(Blueprint b, int era, RandomSource r) {
		int lo = era == 0 ? 1 : 2, hi = era == 0 ? 10 : 9;
		b.hollow(1, 1, 1, 10, 3, 10);
		Block[] crops = {Blocks.WHEAT, Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS};
		for (int x = lo; x <= hi; x++) {
			Block crop = crops[Math.floorMod(x * 7 + (int) r.nextLong(), crops.length)];
			for (int z = lo; z <= hi; z++) {
				if (z == 5) {
					b.set(x, 0, z, Blocks.WATER);
					continue;
				}
				b.set(x, 0, z, Blocks.FARMLAND);
				CropBlock c = (CropBlock) crop;
				b.set(x, 1, z, c.getStateForAge(Math.min(c.getMaxAge(), r.nextInt(c.getMaxAge() + 2))));
			}
		}
		if (era > 0) {
			for (int i = 1; i <= 10; i++) {
				b.set(i, 1, 1, Blocks.OAK_FENCE).set(i, 1, 10, Blocks.OAK_FENCE).set(1, 1, i, Blocks.OAK_FENCE).set(10, 1, i, Blocks.OAK_FENCE);
			}
			b.gate(5, 1, 1, Direction.NORTH);
			// scarecrow
			b.set(10, 2, 10, Blocks.OAK_FENCE).set(10, 3, 10, Blocks.HAY_BLOCK).set(10, 4, 10, Blocks.CARVED_PUMPKIN);
		}
		b.set(1, 1, 5, Blocks.COMPOSTER);
	}

	static void lumber(Blueprint b, Palette p) {
		b.hollow(1, 1, 1, 10, 5, 10);
		// shelter on posts
		b.corners(2, 2, 5, 5, 1, 3, p.frame);
		b.fill(1, 4, 1, 6, 4, 6, p.roofTop);
		b.set(3, 1, 3, Blocks.CRAFTING_TABLE);
		b.set(4, 1, 4, Blocks.CHEST);
		// log piles
		for (int x = 7; x <= 9; x++) for (int y = 1; y <= 2; y++) b.log(x, y, 3, Blocks.OAK_LOG, Direction.Axis.Z).log(x, y, 4, Blocks.OAK_LOG, Direction.Axis.Z);
		b.log(8, 3, 3, Blocks.SPRUCE_LOG, Direction.Axis.Z);
		// chopping stump
		b.set(3, 1, 8, Blocks.STRIPPED_OAK_LOG);
		// young trees
		b.set(7, 1, 8, Blocks.OAK_SAPLING).set(9, 1, 7, Blocks.BIRCH_SAPLING).set(6, 1, 9, Blocks.SPRUCE_SAPLING);
		b.set(2, 1, 8, p.light.is(Blocks.LANTERN) ? p.light : Blocks.TORCH.defaultBlockState());
	}

	static void quarry(Blueprint b, int era) {
		b.hollow(1, 1, 1, 10, 4, 10);
		for (int d = 0; d < 4; d++) {
			int lo = 2 + d, hi = 9 - d;
			if (lo > hi) break;
			b.hollow(lo, -d, lo, hi, -d, hi);
			b.fill(lo, -d - 1, lo, hi, -d - 1, hi, Blocks.STONE.defaultBlockState());
		}
		b.set(1, 1, 1, Blocks.COBBLESTONE).set(1, 2, 1, Blocks.COBBLESTONE).set(2, 1, 1, Blocks.COBBLESTONE).set(1, 1, 2, Blocks.COBBLESTONE);
		// a simple crane
		b.fill(10, 1, 10, 10, 5, 10, Blocks.OAK_FENCE.defaultBlockState());
		b.fill(7, 5, 10, 9, 5, 10, Blocks.OAK_PLANKS.defaultBlockState());
		b.set(7, 4, 10, Blocks.IRON_CHAIN);
		if (era >= 2) b.set(10, 1, 1, Blocks.STONECUTTER);
	}

	static void mine(Blueprint b, Palette p) {
		b.hollow(2, 1, 2, 8, 6, 8);
		b.fill(3, 0, 3, 7, 0, 7, p.found);
		b.walls(3, 3, 7, 7, 1, 3, p.wall);
		b.corners(3, 3, 7, 7, 1, 3, p.frame);
		b.roof(2, 2, 8, 8, 4, p.roof, p.roofTop);
		b.door(5, 1, 3, p.door, Direction.NORTH);
		// shaft with a ladder
		b.hollow(5, -12, 5, 5, 0, 5);
		b.fill(5, -12, 6, 5, 0, 6, Blocks.COBBLESTONE.defaultBlockState());
		b.ladder(5, -12, 0, 5, Direction.NORTH);
		b.set(4, 1, 6, Blocks.CHEST).set(6, 1, 6, p.light);
		b.fill(4, 1, 1, 6, 1, 2, Blocks.AIR.defaultBlockState());
		b.set(5, 1, 1, Blocks.RAIL).set(5, 1, 2, Blocks.RAIL);
		b.set(3, 1, 1, Blocks.IRON_ORE).set(7, 1, 1, Blocks.COAL_ORE);
	}

	static void market(Blueprint b, Palette p, BlockState cloth) {
		b.hollow(1, 1, 1, 10, 5, 10);
		b.fill(1, 0, 1, 10, 0, 10, p.road);
		int[][] stalls = {{2, 2}, {6, 2}, {2, 6}, {6, 6}};
		for (int i = 0; i < stalls.length; i++) {
			int x = stalls[i][0], z = stalls[i][1];
			b.corners(x, z, x + 2, z + 2, 1, 2, Blocks.OAK_FENCE.defaultBlockState());
			for (int dx = 0; dx <= 2; dx++) for (int dz = 0; dz <= 2; dz++) {
				b.set(x + dx, 3, z + dz, (dx + i) % 2 == 0 ? cloth : Blocks.WHITE_WOOL.defaultBlockState());
			}
			b.set(x + 1, 1, z + 1, i % 2 == 0 ? Blocks.BARREL : Blocks.COMPOSTER);
		}
		b.set(5, 1, 5, Blocks.BELL);
		b.set(9, 1, 9, Blocks.HAY_BLOCK).set(1, 1, 9, p.light.is(Blocks.TORCH) ? p.light : Blocks.LANTERN.defaultBlockState());
	}

	private Structures() {}
}
