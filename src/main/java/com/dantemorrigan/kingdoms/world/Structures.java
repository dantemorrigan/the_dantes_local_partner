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
			case KEEP -> keep(b, p, era, cloth, dye);
			case TOWN_HALL -> townHall(b, p, cloth);
			case HOUSE -> house(b, p, r);
			case FARM -> farm(b, era, r);
			case LUMBER_CAMP -> lumber(b, p);
			case QUARRY -> quarry(b, era);
			case MINE -> mine(b, p);
			case BARRACKS -> barracks(b, p, cloth);
			case SMITHY -> smithy(b, p, era);
			case MARKET -> market(b, p, cloth);
			case TEMPLE -> temple(b, p, era);
			case LIBRARY -> library(b, p);
			case WALL_TOWER -> tower(b, p, cloth);
			case WORKSHOP -> workshop(b, p);
			case FACTORY -> factory(b, p);
		}
		return b;
	}

	// ---------------------------------------------------------------- designs

	static void house(Blueprint b, Palette p, RandomSource r) {
		int x1 = 2, z1 = 2, x2 = 8 + (r.nextBoolean() ? 1 : 0), z2 = 7 + (r.nextBoolean() ? 1 : 0);
		b.hollow(x1, 1, z1, x2, 6, z2);
		b.fill(x1, 0, z1, x2, 0, z2, p.found);
		b.fill(x1 + 1, 0, z1 + 1, x2 - 1, 0, z2 - 1, p.floor);
		b.walls(x1, z1, x2, z2, 1, 3, p.wall);
		b.corners(x1, z1, x2, z2, 1, 3, p.frame);
		b.door(5, 1, z1, p.door, Direction.NORTH);
		b.set(x1, 2, (z1 + z2) / 2, p.window).set(x2, 2, (z1 + z2) / 2, p.window).set(3, 2, z2, p.window).set(x2 - 1, 2, z2, p.window);
		b.roof(x1 - 1, z1 - 1, x2 + 1, z2 + 1, 4, p.roof, p.roofTop);
		b.set(x1 + 1, 1, z2 - 1, Blocks.CRAFTING_TABLE).set(x2 - 1, 1, z2 - 1, Blocks.CHEST).set(x1 + 1, 1, z1 + 1, p.light.is(Blocks.TORCH) ? p.light : Blocks.TORCH.defaultBlockState());
		b.set(x2 - 1, 1, z1 + 1, Blocks.BARREL);
		// a little garden
		b.set(3, 1, 1, Blocks.POPPY).set(7, 1, 1, Blocks.DANDELION);
	}

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

	static void barracks(Blueprint b, Palette p, BlockState cloth) {
		b.hollow(1, 1, 2, 9, 7, 8);
		b.fill(1, 0, 2, 9, 0, 8, p.found);
		b.walls(1, 2, 9, 8, 1, 4, p.wall);
		b.corners(1, 2, 9, 8, 1, 4, p.frame);
		b.door(5, 1, 2, p.door, Direction.NORTH);
		for (int x = 3; x <= 7; x += 2) b.set(x, 3, 2, p.window).set(x, 3, 8, p.window);
		b.roof(0, 1, 10, 9, 5, p.roof, p.roofTop);
		b.set(2, 1, 7, Blocks.HAY_BLOCK).set(2, 2, 7, Blocks.TARGET).set(8, 1, 7, Blocks.CHEST).set(5, 1, 7, Blocks.SMITHING_TABLE);
		b.set(5, 4, 5, p.light.is(Blocks.TORCH) ? Blocks.LANTERN.defaultBlockState() : p.light);
		b.flag(1, 5, 9, cloth, true);
		b.flag(9, 5, 9, cloth, false);
	}

	static void smithy(Blueprint b, Palette p, int era) {
		b.hollow(1, 1, 1, 10, 6, 10);
		b.fill(2, 0, 2, 8, 0, 8, p.found);
		b.corners(2, 2, 8, 8, 1, 3, p.frame);
		b.roof(1, 1, 9, 9, 4, p.roof, p.roofTop);
		b.set(4, 1, 4, Blocks.ANVIL).set(6, 1, 6, era >= 2 ? Blocks.BLAST_FURNACE : Blocks.FURNACE).set(7, 1, 6, Blocks.FURNACE);
		b.set(3, 1, 7, Blocks.CAULDRON).set(5, 1, 7, Blocks.GRINDSTONE).set(7, 1, 3, Blocks.SMITHING_TABLE);
		// chimney
		b.fill(8, 1, 8, 8, 7, 8, Blocks.COBBLESTONE.defaultBlockState());
		b.set(8, 8, 8, Blocks.CAMPFIRE);
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

	static void temple(Blueprint b, Palette p, int era) {
		b.hollow(2, 1, 1, 8, 10, 10);
		b.fill(2, 0, 1, 8, 0, 10, p.found);
		b.fill(3, 0, 2, 7, 0, 9, p.floor);
		b.walls(2, 1, 8, 10, 1, 5, era >= 2 ? p.wall : p.fortress);
		b.corners(2, 1, 8, 10, 1, 6, p.frame);
		b.door(5, 1, 1, p.door, Direction.NORTH);
		for (int z = 3; z <= 8; z += 2) b.set(2, 3, z, p.window).set(2, 4, z, p.window).set(8, 3, z, p.window).set(8, 4, z, p.window);
		b.roof(1, 0, 9, 11, 6, p.roof, p.roofTop);
		b.set(5, 1, 9, era >= 3 ? Blocks.ENCHANTING_TABLE : era >= 2 ? Blocks.GOLD_BLOCK : Blocks.CHISELED_STONE_BRICKS);
		b.set(4, 1, 9, Blocks.CANDLE).set(6, 1, 9, Blocks.CANDLE);
		for (int z = 4; z <= 7; z++) b.set(5, 0, z, Blocks.RED_CARPET).set(5, 1, z, Blocks.RED_CARPET);
		// bell tower over the entrance
		b.fill(4, 6, 2, 6, 9, 3, p.fortress);
		b.hollow(5, 8, 2, 5, 8, 3);
		b.set(5, 10, 2, p.roofTop);
	}

	static void library(Blueprint b, Palette p) {
		b.hollow(2, 1, 2, 9, 7, 9);
		b.fill(2, 0, 2, 9, 0, 9, p.found);
		b.fill(3, 0, 3, 8, 0, 8, p.floor);
		b.walls(2, 2, 9, 9, 1, 4, p.wall);
		b.corners(2, 2, 9, 9, 1, 4, p.frame);
		b.door(5, 1, 2, p.door, Direction.NORTH);
		b.set(2, 3, 5, p.window).set(9, 3, 5, p.window).set(2, 3, 6, p.window).set(9, 3, 6, p.window);
		b.roof(1, 1, 10, 10, 5, p.roof, p.roofTop);
		for (int x = 3; x <= 8; x++) b.set(x, 1, 8, Blocks.BOOKSHELF).set(x, 2, 8, Blocks.BOOKSHELF);
		for (int z = 4; z <= 7; z++) b.set(3, 1, z, Blocks.BOOKSHELF).set(8, 1, z, Blocks.BOOKSHELF);
		b.set(5, 1, 5, Blocks.LECTERN).set(6, 1, 5, Blocks.CARTOGRAPHY_TABLE).set(5, 4, 5, Blocks.LANTERN);
	}

	static void tower(Blueprint b, Palette p, BlockState cloth) {
		b.hollow(3, 1, 3, 7, 12, 7);
		b.fill(3, 0, 3, 7, 0, 7, p.found);
		b.walls(3, 3, 7, 7, 1, 8, p.fortress);
		b.door(5, 1, 3, p.door, Direction.NORTH);
		b.set(3, 5, 5, Blocks.AIR).set(7, 5, 5, Blocks.AIR).set(5, 5, 7, Blocks.AIR);
		b.fill(4, 8, 4, 6, 8, 6, p.floor);
		b.ladder(6, 1, 8, 6, Direction.NORTH);
		b.set(6, 8, 6, Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.NORTH));
		b.battlements(3, 3, 7, 7, 9, p.fortress);
		b.flag(5, 9, 5, cloth, true);
		b.set(4, 9, 4, Blocks.LANTERN);
	}

	static void workshop(Blueprint b, Palette p) {
		b.hollow(1, 1, 2, 9, 7, 9);
		b.fill(1, 0, 2, 9, 0, 9, p.found);
		b.walls(1, 2, 9, 9, 1, 4, p.wall);
		b.corners(1, 2, 9, 9, 1, 4, p.frame);
		b.door(5, 1, 2, p.door, Direction.NORTH);
		for (int x = 3; x <= 7; x += 2) b.set(x, 2, 2, p.window).set(x, 3, 2, p.window).set(x, 2, 9, p.window);
		b.roof(0, 1, 10, 10, 5, p.roof, p.roofTop);
		b.set(2, 1, 8, Blocks.LOOM).set(3, 1, 8, Blocks.STONECUTTER).set(4, 1, 8, Blocks.SMITHING_TABLE).set(6, 1, 8, Blocks.CARTOGRAPHY_TABLE)
				.set(7, 1, 8, Blocks.FLETCHING_TABLE).set(8, 1, 8, Blocks.CRAFTER).set(5, 4, 5, Blocks.LANTERN);
	}

	static void factory(Blueprint b, Palette p) {
		b.hollow(1, 1, 1, 10, 8, 10);
		b.fill(1, 0, 1, 10, 0, 10, p.found);
		b.walls(1, 1, 10, 10, 1, 5, p.wall);
		b.corners(1, 1, 10, 10, 1, 5, p.frame);
		b.door(5, 1, 1, Blocks.IRON_DOOR, Direction.NORTH);
		for (int x = 3; x <= 8; x += 2) for (int y = 2; y <= 3; y++) b.set(x, y, 1, Blocks.IRON_BARS).set(x, y, 10, Blocks.IRON_BARS);
		b.fill(1, 6, 1, 10, 6, 10, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
		// chimney stacks
		b.fill(8, 1, 8, 9, 14, 9, Blocks.BRICKS.defaultBlockState());
		b.set(8, 15, 8, Blocks.CAMPFIRE).set(9, 15, 9, Blocks.CAMPFIRE);
		b.fill(2, 1, 8, 2, 9, 8, Blocks.BRICKS.defaultBlockState());
		b.set(2, 10, 8, Blocks.CAMPFIRE);
		b.set(3, 1, 3, Blocks.BLAST_FURNACE).set(4, 1, 3, Blocks.BLAST_FURNACE).set(6, 1, 3, Blocks.PISTON).set(7, 1, 3, Blocks.OBSERVER);
		b.set(3, 1, 6, Blocks.HOPPER).set(4, 1, 6, Blocks.CHEST).set(5, 4, 5, Blocks.LANTERN).set(6, 1, 6, Blocks.ANVIL);
	}

	static void townHall(Blueprint b, Palette p, BlockState cloth) {
		b.hollow(1, 1, 1, 10, 9, 10);
		b.fill(1, 0, 1, 10, 0, 10, p.road);
		b.fill(2, 0, 2, 9, 0, 9, p.found);
		b.fill(3, 0, 3, 8, 0, 8, p.floor);
		b.walls(2, 2, 9, 9, 1, 4, p.wall);
		b.corners(2, 2, 9, 9, 1, 5, p.frame);
		b.door(5, 1, 2, p.door, Direction.NORTH);
		b.door(6, 1, 2, p.door, Direction.NORTH);
		b.set(2, 3, 5, p.window).set(9, 3, 5, p.window).set(4, 3, 9, p.window).set(7, 3, 9, p.window);
		b.roof(1, 1, 10, 10, 5, p.roof, p.roofTop);
		b.set(5, 1, 8, Blocks.LECTERN).set(4, 1, 8, Blocks.CHEST).set(5, 4, 5, Blocks.LANTERN);
		b.set(5, 9, 5, Blocks.BELL);
		b.flag(1, 1, 1, cloth, true);
	}

	static void keep(Blueprint b, Palette p, int era, BlockState cloth, int dye) {
		BlockState wall = era == 0 ? Blocks.OAK_LOG.defaultBlockState() : era == 1 ? Blocks.COBBLESTONE.defaultBlockState() : p.fortress;
		b.hollow(0, 1, 0, 11, 16, 11);
		b.fill(0, 0, 0, 11, 0, 11, p.road);
		b.fill(2, 0, 2, 9, 0, 9, p.found);
		b.fill(3, 0, 3, 8, 0, 8, p.floor);
		int h = 4 + Math.min(era, 3) * 2;
		b.walls(2, 2, 9, 9, 1, h, wall);
		b.door(5, 1, 2, p.door, Direction.NORTH);
		b.door(6, 1, 2, p.door, Direction.NORTH);
		for (int y = 3; y < h; y += 3) {
			b.set(2, y, 5, p.window).set(9, y, 5, p.window).set(2, y, 6, p.window).set(9, y, 6, p.window);
		}
		// upper floor
		b.fill(3, h - 1, 3, 8, h - 1, 8, p.floor);
		b.hollow(7, h - 1, 7, 7, h - 1, 7);
		b.ladder(7, 1, h, 8, Direction.NORTH);
		b.fill(2, h + 1, 2, 9, h + 1, 9, p.floor);
		b.battlements(2, 2, 9, 9, h + 2, wall);
		// corner towers
		int th = h + 3;
		int[][] cs = {{1, 1}, {9, 1}, {1, 9}, {9, 9}};
		for (int[] c : cs) {
			b.fill(c[0], 1, c[1], c[0] + 1, th, c[1] + 1, wall);
			b.set(c[0], th + 1, c[1], wall).set(c[0] + 1, th + 1, c[1] + 1, wall);
		}
		b.flag(1, th + 1, 2, cloth, false);
		b.flag(10, th + 1, 9, cloth, true);
		// the throne room
		BlockState carpet = CARPET[Math.floorMod(dye, 16)].defaultBlockState();
		for (int z = 3; z <= 7; z++) b.set(5, 1, z, carpet).set(6, 1, z, carpet);
		b.set(5, 1, 8, era >= 1 ? Blocks.GOLD_BLOCK : Blocks.CHISELED_STONE_BRICKS).set(6, 1, 8, era >= 1 ? Blocks.GOLD_BLOCK : Blocks.CHISELED_STONE_BRICKS);
		b.set(5, 2, 8, Blocks.OAK_STAIRS).set(6, 2, 8, Blocks.OAK_STAIRS);
		b.set(5, 3, 9, cloth).set(6, 3, 9, cloth);
		b.set(3, 1, 3, p.light.is(Blocks.TORCH) ? Blocks.LANTERN.defaultBlockState() : p.light).set(8, 1, 3, Blocks.LANTERN.defaultBlockState());
		b.set(3, 1, 8, Blocks.CHEST).set(8, 1, 8, Blocks.CHEST);
	}

	private Structures() {}
}
