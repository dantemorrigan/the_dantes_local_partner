package com.dantemorrigan.kingdoms.world;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Buildings that grow with their age. A hut of logs and thatch; a timber house with a chimney; a stone-based house
 * under a tiled roof; a white three-storey town house with balconies; a five-storey brick tenement with a flat roof
 * and iron balconies. Keeps become palaces with clock towers, workshops become mills with sawtooth roofs, and the
 * industrial age raises tall factories with smokestacks, foundries, a railway station and a university.
 */
final class Architecture {
	/** Materials of one age for walls (base floor, upper floors), trim, and glass. */
	record Mats(BlockState base, BlockState upper, BlockState trim, BlockState glass) {}

	static BlockState b(Block x) {
		return x.defaultBlockState();
	}

	static Mats mats(int era) {
		return switch (era) {
			case 0 -> new Mats(b(Blocks.STRIPPED_OAK_LOG), b(Blocks.STRIPPED_OAK_LOG), b(Blocks.OAK_LOG), b(Blocks.AIR));
			case 1 -> new Mats(b(Blocks.COBBLESTONE), b(Blocks.OAK_PLANKS), b(Blocks.OAK_LOG), b(Blocks.GLASS_PANE));
			case 2 -> new Mats(b(Blocks.STONE_BRICKS), b(Blocks.SPRUCE_PLANKS), b(Blocks.DARK_OAK_LOG), b(Blocks.GLASS_PANE));
			case 3 -> new Mats(b(Blocks.STONE_BRICKS), b(Blocks.WHITE_TERRACOTTA), b(Blocks.STRIPPED_DARK_OAK_LOG), b(Blocks.GLASS_PANE));
			default -> new Mats(b(Blocks.STONE_BRICKS), b(Blocks.BRICKS), b(Blocks.SMOOTH_STONE), b(Blocks.GLASS_PANE));
		};
	}

	static BlockState full(BlockState slab) {
		return slab.setValue(SlabBlock.TYPE, SlabType.DOUBLE);
	}

	/**
	 * Walls, floors, windows, a door in the front (low z) and a ladder shaft for a block of {@code floors} storeys of
	 * three blocks each. Returns the y of the first free block above the top wall.
	 */
	static int body(Blueprint bp, Structures.Palette p, int era, int x1, int z1, int x2, int z2, int floors, boolean tallWindows) {
		Mats m = mats(era);
		int h = floors * 3;
		bp.hollow(x1, 1, z1, x2, h + 1, z2);
		bp.fill(x1, 0, z1, x2, 0, z2, p.found());
		bp.fill(x1 + 1, 0, z1 + 1, x2 - 1, 0, z2 - 1, p.floor());
		int mid = (x1 + x2) / 2;
		for (int f = 0; f < floors; f++) {
			int y0 = 1 + f * 3;
			BlockState wall = f == 0 ? m.base() : m.upper();
			bp.walls(x1, z1, x2, z2, y0, y0 + 2, wall);
			bp.corners(x1, z1, x2, z2, y0, y0 + 2, m.trim());
			if (f >= 1) {
				bp.fill(x1 + 1, y0 - 1, z1 + 1, x2 - 1, y0 - 1, z2 - 1, p.floor());
				bp.hollow(x2 - 1, y0 - 1, z2 - 1, x2 - 1, y0 - 1, z2 - 1);
				if (era >= 3) bp.walls(x1, z1, x2, z2, y0 - 1, y0 - 1, m.trim()); // a band between the storeys
			}
			if (m.glass().isAir()) {
				bp.set(mid, y0 + 1, z1 + 1, Blocks.AIR); // a smoke-darkened hut has no glass
			} else {
				for (int x = x1 + 1; x < x2; x += 2) {
					if (f == 0 && x == mid) continue;
					for (int dy = 1; dy <= (tallWindows ? 2 : 1); dy++) {
						bp.set(x, y0 + dy, z1, m.glass()).set(x, y0 + dy, z2, m.glass());
					}
				}
				for (int z = z1 + 2; z < z2; z += 2) {
					bp.set(x1, y0 + 1, z, m.glass()).set(x2, y0 + 1, z, m.glass());
				}
			}
		}
		bp.door(mid, 1, z1, p.door(), Direction.NORTH);
		bp.ladder(x2 - 1, 1, h, z2 - 1, Direction.NORTH);
		return h + 1;
	}

	/** A chimney on the outside of a wall, with a fire that smokes. */
	static void chimney(Blueprint bp, int x, int z, int top, boolean brick, int kind) {
		BlockState st = brick ? b(Blocks.BRICKS) : b(Blocks.COBBLESTONE);
		bp.fill(x, 1, z, x, top, z, st);
		bp.set(x, top + 1, z, Blocks.CAMPFIRE);
		bp.emit(x, top + 2, z, kind);
	}

	// ------------------------------------------------------------------ houses

	static void hut(Blueprint bp, Structures.Palette p, RandomSource r) {
		int x1 = 3, z1 = 3, x2 = 7, z2 = 7;
		bp.hollow(2, 1, 2, 8, 9, 8);
		bp.fill(x1, 0, z1, x2, 0, z2, b(Blocks.COARSE_DIRT));
		bp.walls(x1, z1, x2, z2, 1, 3, b(Blocks.STRIPPED_OAK_LOG));
		bp.corners(x1, z1, x2, z2, 1, 3, b(Blocks.OAK_LOG));
		bp.door(5, 1, z1, Blocks.OAK_DOOR, Direction.NORTH);
		// thatched cone with a smoke hole
		BlockState hay = b(Blocks.HAY_BLOCK);
		bp.fill(2, 4, 2, 8, 4, 8, hay);
		bp.fill(3, 5, 3, 7, 5, 7, hay);
		bp.fill(4, 6, 4, 6, 6, 6, hay);
		bp.set(5, 7, 5, Blocks.AIR);
		bp.set(5, 1, 5, Blocks.CAMPFIRE).set(4, 1, 6, Blocks.BARREL).set(6, 1, 4, Blocks.CRAFTING_TABLE);
		bp.emit(5, 7, 5, 1);
		bp.set(2, 1, 1, Blocks.POPPY).set(8, 1, 1, Blocks.DANDELION);
	}

	static void house(Blueprint bp, Structures.Palette p, int era, RandomSource r) {
		if (era == 0) {
			hut(bp, p, r);
			return;
		}
		int x1 = 2, z1 = 2, x2 = 8 + (r.nextBoolean() ? 1 : 0), z2 = 7 + (r.nextBoolean() ? 1 : 0);
		int floors = switch (era) {
			case 1, 2 -> 2;
			case 3 -> 3 + r.nextInt(2);
			default -> 4 + r.nextInt(2);
		};
		int roofY = body(bp, p, era, x1, z1, x2, z2, floors, era >= 4);
		if (era >= 4) {
			// a flat tenement roof with a parapet, water tanks and two chimney stacks
			bp.fill(x1, roofY, z1, x2, roofY, z2, b(Blocks.SMOOTH_STONE));
			bp.walls(x1, z1, x2, z2, roofY + 1, roofY + 1, b(Blocks.STONE_BRICK_WALL));
			bp.fill(x1 + 1, roofY + 1, z1 + 1, x1 + 2, roofY + 2, z1 + 2, b(Blocks.BARREL));
			chimney(bp, x2 - 1, z2 - 1, roofY + 5, true, 1);
			chimney(bp, x1 + 1, z2 - 1, roofY + 4, true, 1);
			// iron balconies on the upper storeys
			for (int f = 1; f < floors; f++) {
				int y = 1 + f * 3;
				for (int x = x1 + 1; x < x2; x++) bp.set(x, y, z1 - 1, Blocks.SMOOTH_STONE_SLAB).set(x, y + 1, z1 - 1, Blocks.IRON_BARS);
			}
		} else {
			bp.roof(x1 - 1, z1 - 1, x2 + 1, z2 + 1, roofY, p.roof(), p.roofTop());
			chimney(bp, x2 + 2 > 10 ? x2 : x2 + 1, z1 + 2, roofY + 3, era >= 3, 1);
			if (era >= 3) { // wooden balconies on the upper storeys
				for (int f = 1; f < Math.min(floors, 3); f++) {
					int y = 1 + f * 3;
					for (int x = x1 + 1; x < x2; x++) bp.set(x, y, z1 - 1, Blocks.SPRUCE_SLAB).set(x, y + 1, z1 - 1, Blocks.OAK_FENCE);
					bp.set(x1 + 2, y + 1, z1 - 1, Blocks.FLOWERING_AZALEA);
				}
			}
		}
		bp.set(x1 + 1, 1, z2 - 1, Blocks.CRAFTING_TABLE).set(x2 - 2, 1, z2 - 1, Blocks.CHEST).set(x1 + 1, 1, z1 + 1, Blocks.TORCH);
		bp.set(3, 1, 1, Blocks.POPPY).set(7, 1, 1, Blocks.DANDELION);
	}

	// ------------------------------------------------------------------ the powers

	static void keep(Blueprint bp, Structures.Palette p, int era, BlockState cloth, int dye) {
		Mats m = mats(era);
		BlockState wall = era == 0 ? b(Blocks.OAK_LOG) : era == 1 ? b(Blocks.COBBLESTONE) : era == 2 ? b(Blocks.STONE_BRICKS) : era == 3 ? b(Blocks.STONE_BRICKS) : b(Blocks.POLISHED_ANDESITE);
		int floors = switch (era) {
			case 0 -> 2;
			case 1 -> 3;
			case 2 -> 4;
			case 3 -> 5;
			default -> 7;
		};
		int h = floors * 4;
		bp.hollow(0, 1, 0, 11, h + 24, 11);
		bp.fill(0, 0, 0, 11, 0, 11, p.road());
		bp.fill(2, 0, 2, 9, 0, 9, p.found());
		bp.fill(3, 0, 3, 8, 0, 8, p.floor());
		bp.walls(2, 2, 9, 9, 1, h, wall);
		bp.door(5, 1, 2, p.door(), Direction.NORTH);
		bp.door(6, 1, 2, p.door(), Direction.NORTH);
		for (int f = 0; f < floors; f++) {
			int y0 = 1 + f * 4;
			if (f >= 1) {
				bp.fill(3, y0 - 1, 3, 8, y0 - 1, 8, p.floor());
				bp.hollow(7, y0 - 1, 7, 7, y0 - 1, 7);
				if (era >= 2) bp.walls(2, 2, 9, 9, y0 - 1, y0 - 1, m.trim());
			}
			for (int off : new int[]{4, 5, 6, 7}) {
				if (era == 0 && f > 0 && off != 5) continue;
				if (f > 0 || off > 5) bp.set(2, y0 + 1, off, era >= 2 ? Blocks.GLASS_PANE : Blocks.AIR).set(9, y0 + 1, off, era >= 2 ? Blocks.GLASS_PANE : Blocks.AIR);
			}
			if (f > 0) for (int off : new int[]{4, 7}) bp.set(off, y0 + 1, 9, era >= 2 ? Blocks.GLASS_PANE : Blocks.AIR);
		}
		bp.ladder(7, 1, h, 8, Direction.NORTH);
		// the roof platform with battlements
		bp.fill(2, h + 1, 2, 9, h + 1, 9, p.floor());
		bp.battlements(2, 2, 9, 9, h + 2, wall);
		// corner towers, taller and taller
		int th = h + 3 + Math.min(era, 4) * 2;
		int[][] cs = {{1, 1}, {9, 1}, {1, 9}, {9, 9}};
		for (int[] c : cs) {
			bp.fill(c[0], 1, c[1], c[0] + 1, th, c[1] + 1, wall);
			if (era >= 3) { // pointed roofs
				bp.set(c[0] - 0, th + 1, c[1], p.roofTop()).set(c[0] + 1, th + 1, c[1] + 1, p.roofTop()).set(c[0] + 1, th + 1, c[1], p.roofTop()).set(c[0], th + 1, c[1] + 1, p.roofTop());
				bp.set(c[0], th + 2, c[1], p.roofTop());
			} else {
				bp.set(c[0], th + 1, c[1], wall).set(c[0] + 1, th + 1, c[1] + 1, wall);
			}
		}
		bp.flag(1, th + 1, 2, cloth, false);
		bp.flag(10, th + 1, 9, cloth, true);
		if (era >= 4) {
			// the palace: a clock tower rising from the middle, glass, iron bars, a lantern on top
			bp.fill(4, h + 2, 4, 7, h + 12, 7, wall);
			bp.hollow(5, h + 3, 5, 6, h + 11, 6);
			for (int y = h + 4; y <= h + 10; y += 3) {
				bp.set(4, y, 5, Blocks.GLASS_PANE).set(7, y, 6, Blocks.GLASS_PANE).set(5, y, 4, Blocks.GLASS_PANE).set(6, y, 7, Blocks.GLASS_PANE);
			}
			bp.set(5, h + 8, 3, Blocks.WHITE_CONCRETE).set(6, h + 8, 3, Blocks.WHITE_CONCRETE);
			bp.fill(4, h + 13, 4, 7, h + 13, 7, p.roofTop());
			bp.fill(5, h + 14, 5, 6, h + 14, 6, p.roofTop());
			bp.set(5, h + 15, 5, Blocks.LANTERN);
			bp.emit(5, h + 14, 5, 2);
		}
		// the throne room
		BlockState carpet = Structures.CARPET[Math.floorMod(dye, 16)].defaultBlockState();
		for (int z = 3; z <= 7; z++) bp.set(5, 1, z, carpet).set(6, 1, z, carpet);
		bp.set(5, 1, 8, era >= 1 ? Blocks.GOLD_BLOCK : Blocks.CHISELED_STONE_BRICKS).set(6, 1, 8, era >= 1 ? Blocks.GOLD_BLOCK : Blocks.CHISELED_STONE_BRICKS);
		bp.set(5, 2, 8, Blocks.OAK_STAIRS).set(6, 2, 8, Blocks.OAK_STAIRS);
		bp.set(5, 3, 9, cloth).set(6, 3, 9, cloth);
		bp.set(3, 1, 3, Blocks.LANTERN).set(8, 1, 3, Blocks.LANTERN).set(3, 1, 8, Blocks.CHEST).set(8, 1, 8, Blocks.CHEST);
	}

	static void townHall(Blueprint bp, Structures.Palette p, int era, BlockState cloth) {
		int floors = switch (era) {
			case 0, 1 -> 1;
			case 2 -> 2;
			case 3 -> 3;
			default -> 4;
		};
		bp.hollow(1, 1, 1, 10, floors * 3 + 16, 10);
		bp.fill(1, 0, 1, 10, 0, 10, p.road());
		int roofY = body(bp, p, era, 2, 2, 9, 9, floors, era >= 3);
		bp.door(6, 1, 2, p.door(), Direction.NORTH);
		if (era <= 3) bp.roof(1, 1, 10, 10, roofY, p.roof(), p.roofTop());
		else {
			bp.fill(2, roofY, 2, 9, roofY, 9, b(Blocks.SMOOTH_STONE));
			bp.walls(2, 2, 9, 9, roofY + 1, roofY + 1, b(Blocks.STONE_BRICK_WALL));
		}
		// the bell and, from the age of cities, a clock tower
		if (era >= 3) {
			int ty = roofY + (era >= 4 ? 1 : 0);
			bp.fill(7, ty, 7, 9, ty + 8, 9, mats(era).trim().is(Blocks.SMOOTH_STONE) ? b(Blocks.STONE_BRICKS) : mats(era).base());
			bp.hollow(8, ty + 1, 8, 8, ty + 7, 8);
			bp.set(8, ty + 5, 7, Blocks.WHITE_CONCRETE).set(7, ty + 5, 8, Blocks.WHITE_CONCRETE);
			bp.fill(7, ty + 9, 7, 9, ty + 9, 9, p.roofTop());
			bp.set(8, ty + 10, 8, p.roofTop()).set(8, ty + 11, 8, Blocks.LIGHTNING_ROD);
		} else {
			bp.set(5, roofY + 4, 5, Blocks.BELL);
		}
		bp.set(5, 1, 8, Blocks.LECTERN).set(4, 1, 8, Blocks.CHEST).set(5, 3, 5, Blocks.LANTERN);
		bp.flag(1, 1, 1, cloth, true);
	}

	/** A hall in front and a fenced drill yard behind it where the garrison stands in ranks (plot z 6..10). */
	static void barracks(Blueprint bp, Structures.Palette p, int era, BlockState cloth) {
		int floors = era >= 3 ? 3 : era >= 2 ? 2 : 1;
		int roofY = body(bp, p, era, 1, 1, 10, 5, floors, false);
		bp.door(5, 1, 5, p.door(), Direction.SOUTH); // the way out to the yard
		if (era >= 2) { // crenellated roof and corner turrets
			bp.fill(1, roofY, 1, 10, roofY, 5, p.floor());
			bp.battlements(1, 1, 10, 5, roofY + 1, mats(era).base());
			for (int[] c : new int[][]{{1, 1}, {10, 1}, {1, 5}, {10, 5}}) bp.fill(c[0], roofY + 1, c[1], c[0], roofY + 3, c[1], mats(era).base());
		} else bp.roof(0, 0, 11, 6, roofY, p.roof(), p.roofTop());
		bp.set(2, 1, 2, Blocks.HAY_BLOCK).set(2, 2, 2, Blocks.TARGET).set(9, 1, 2, Blocks.CHEST).set(5, 1, 2, Blocks.SMITHING_TABLE);
		bp.set(3, 1, 4, Blocks.RED_BED).set(8, 1, 4, Blocks.WHITE_BED);
		// the yard
		bp.hollow(0, 1, 6, 11, 5, 11);
		BlockState ground = era >= 2 ? b(Blocks.STONE_BRICKS) : b(Blocks.GRAVEL);
		bp.fill(1, 0, 6, 10, 0, 10, ground);
		BlockState fence = era >= 2 ? b(Blocks.STONE_BRICK_WALL) : b(Blocks.OAK_FENCE);
		for (int z = 6; z <= 10; z++) bp.set(1, 1, z, fence).set(10, 1, z, fence);
		for (int x = 1; x <= 10; x++) if (x != 5 && x != 6) bp.set(x, 1, 10, fence);
		bp.set(2, 1, 6, Blocks.HAY_BLOCK).set(2, 2, 6, Blocks.CARVED_PUMPKIN).set(9, 1, 6, Blocks.HAY_BLOCK).set(9, 2, 6, Blocks.CARVED_PUMPKIN);
		bp.set(1, 2, 10, Blocks.LANTERN).set(10, 2, 10, Blocks.LANTERN);
		bp.flag(0, 1, 8, cloth, true);
		bp.flag(11, 1, 8, cloth, false);
	}

	static void temple(Blueprint bp, Structures.Palette p, int era) {
		int wallH = 5 + era * 2;
		BlockState wall = era <= 1 ? b(Blocks.COBBLESTONE) : era == 2 ? b(Blocks.STONE_BRICKS) : era == 3 ? b(Blocks.WHITE_TERRACOTTA) : b(Blocks.QUARTZ_BRICKS);
		bp.hollow(2, 1, 1, 8, wallH + 20, 10);
		bp.fill(2, 0, 1, 8, 0, 10, p.found());
		bp.fill(3, 0, 2, 7, 0, 9, p.floor());
		bp.walls(2, 1, 8, 10, 1, wallH, wall);
		bp.corners(2, 1, 8, 10, 1, wallH + 1, mats(era).trim());
		bp.door(5, 1, 1, p.door(), Direction.NORTH);
		for (int z = 3; z <= 8; z += 2) {
			for (int y = 3; y <= wallH - 1; y++) bp.set(2, y, z, Blocks.GLASS_PANE).set(8, y, z, Blocks.GLASS_PANE);
		}
		bp.roof(1, 0, 9, 11, wallH + 1, p.roof(), p.roofTop());
		bp.set(5, 1, 9, era >= 3 ? Blocks.ENCHANTING_TABLE : era >= 2 ? Blocks.GOLD_BLOCK : Blocks.CHISELED_STONE_BRICKS);
		bp.set(4, 1, 9, Blocks.CANDLE).set(6, 1, 9, Blocks.CANDLE);
		for (int z = 4; z <= 7; z++) bp.set(5, 0, z, Blocks.RED_CARPET).set(5, 1, z, Blocks.RED_CARPET);
		// a bell tower over the entrance whose spire grows with the age
		int tower = wallH + 3 + era * 2;
		bp.fill(4, 1, 1, 6, tower, 3, wall);
		bp.hollow(5, 2, 2, 5, tower - 4, 2);
		bp.ladder(5, 2, tower - 4, 2, Direction.SOUTH);
		bp.hollow(5, tower - 3, 1, 5, tower - 1, 3);
		bp.set(5, tower - 2, 2, Blocks.BELL);
		for (int y = tower + 1; y <= tower + 3 + era; y++) {
			int w = Math.max(0, 1 - (y - tower - 1) / 2);
			bp.fill(5 - w, y, 2 - Math.min(w, 1), 5 + w, y, 2 + Math.min(w, 1), p.roofTop());
		}
	}

	static void library(Blueprint bp, Structures.Palette p, int era) {
		int floors = era >= 4 ? 3 : 2;
		int roofY = body(bp, p, era, 2, 2, 9, 9, floors, true);
		bp.roof(1, 1, 10, 10, roofY, p.roof(), p.roofTop());
		for (int f = 0; f < floors; f++) {
			int y = 1 + f * 3;
			for (int x = 3; x <= 8; x++) bp.set(x, y, 8, Blocks.BOOKSHELF).set(x, y + 1, 8, Blocks.BOOKSHELF);
			for (int z = 4; z <= 7; z++) bp.set(3, y, z, Blocks.BOOKSHELF);
		}
		bp.set(5, 1, 5, Blocks.LECTERN).set(6, 1, 5, Blocks.CARTOGRAPHY_TABLE).set(5, 3, 5, Blocks.LANTERN);
	}

	static void tower(Blueprint bp, Structures.Palette p, int era, BlockState cloth) {
		int h = 9 + era * 2;
		BlockState wall = era == 0 ? b(Blocks.OAK_LOG) : era == 1 ? b(Blocks.COBBLESTONE) : era == 2 ? b(Blocks.STONE_BRICKS) : era == 3 ? b(Blocks.STONE_BRICKS) : b(Blocks.POLISHED_ANDESITE);
		bp.hollow(3, 1, 3, 7, h + 6, 7);
		bp.fill(3, 0, 3, 7, 0, 7, p.found());
		bp.walls(3, 3, 7, 7, 1, h, wall);
		bp.door(5, 1, 3, p.door(), Direction.NORTH);
		for (int y = 4; y < h; y += 4) bp.set(3, y, 5, Blocks.AIR).set(7, y, 5, Blocks.AIR).set(5, y, 7, Blocks.AIR); // arrow slits
		bp.fill(4, h, 4, 6, h, 6, p.floor());
		bp.ladder(6, 1, h, 6, Direction.NORTH);
		bp.set(6, h, 6, Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.NORTH));
		bp.battlements(3, 3, 7, 7, h + 1, wall);
		if (era >= 3) bp.roof(3, 3, 7, 7, h + 2, p.roof(), p.roofTop());
		bp.flag(5, h + 1 + (era >= 3 ? 3 : 0), 5, cloth, true);
		bp.set(4, h + 1, 4, Blocks.TORCH);
	}

	/**
	 * A gate house in the middle of a side of the wall: two towers with battlements and a lintel over a four-wide
	 * passage (plot z axis) with a raised portcullis and lanterns. Guards stand at both mouths, bowmen on the towers.
	 */
	static void gate(Blueprint bp, Structures.Palette p, int era, BlockState cloth) {
		int h = WallWorks.height(era);
		int ht = h + 4;
		BlockState wall = era == 0 ? b(Blocks.SPRUCE_LOG) : era == 1 ? b(Blocks.COBBLESTONE) : era <= 3 ? b(Blocks.STONE_BRICKS) : b(Blocks.POLISHED_ANDESITE);
		bp.hollow(0, 1, 0, 11, ht + 8, 11);
		bp.fill(4, 0, 0, 7, 0, 11, p.road());
		for (int x0 : new int[]{0, 8}) bp.fill(x0, 1, 3, x0 + 3, ht, 8, wall);
		bp.fill(4, h, 3, 7, ht, 8, wall);
		bp.fill(0, 0, 3, 3, 0, 8, p.found());
		bp.fill(8, 0, 3, 11, 0, 8, p.found());
		for (int x = 4; x <= 7; x++) bp.set(x, h - 1, 3, Blocks.IRON_BARS).set(x, h - 1, 8, Blocks.IRON_BARS);
		bp.set(4, h - 2, 5, Blocks.LANTERN).set(7, h - 2, 6, Blocks.LANTERN);
		bp.battlements(0, 3, 11, 8, ht + 1, wall);
		for (int x0 : new int[]{0, 8}) { // arrow slits toward both mouths
			bp.set(x0 + 1, ht - 2, 3, Blocks.AIR).set(x0 + 2, ht - 2, 8, Blocks.AIR);
		}
		bp.flag(1, ht + 2, 4, cloth, true);
		bp.flag(10, ht + 2, 6, cloth, false);
		bp.set(3, 1, 2, Blocks.TORCH).set(8, 1, 2, Blocks.TORCH).set(3, 1, 9, Blocks.TORCH).set(8, 1, 9, Blocks.TORCH);
	}

	// ------------------------------------------------------------------ work

	static void smithy(Blueprint bp, Structures.Palette p, int era) {
		int roofY = body(bp, p, era, 2, 2, 9, 9, era >= 3 ? 2 : 1, false);
		bp.roof(1, 1, 10, 10, roofY, p.roof(), p.roofTop());
		bp.set(4, 1, 4, Blocks.ANVIL).set(6, 1, 6, era >= 2 ? Blocks.BLAST_FURNACE : Blocks.FURNACE).set(7, 1, 6, Blocks.FURNACE);
		bp.set(3, 1, 7, Blocks.CAULDRON).set(5, 1, 7, Blocks.GRINDSTONE).set(7, 1, 3, Blocks.SMITHING_TABLE);
		chimney(bp, 8, 8, roofY + 3, era >= 2, 1);
		bp.emit(8, roofY + 5, 8, 3);
	}

	static void workshop(Blueprint bp, Structures.Palette p, int era) {
		int floors = era >= 4 ? 3 : 2;
		int roofY = body(bp, p, era, 1, 2, 10, 9, floors, true);
		// sawtooth roof: rows of glazed slopes under a row of chimneys
		bp.fill(1, roofY, 2, 10, roofY, 9, b(Blocks.SMOOTH_STONE_SLAB));
		for (int x = 2; x <= 9; x += 3) {
			bp.fill(x, roofY + 1, 2, x, roofY + 2, 9, p.roof());
			bp.fill(x + 1, roofY + 1, 2, x + 1, roofY + 1, 9, Blocks.GLASS);
		}
		bp.set(2, 1, 8, Blocks.LOOM).set(3, 1, 8, Blocks.STONECUTTER).set(4, 1, 8, Blocks.SMITHING_TABLE).set(6, 1, 8, Blocks.CARTOGRAPHY_TABLE)
				.set(7, 1, 8, Blocks.FLETCHING_TABLE).set(8, 1, 8, Blocks.CRAFTER).set(5, 3, 5, Blocks.LANTERN);
		chimney(bp, 9, 3, roofY + 5, true, 1);
	}

	static void factory(Blueprint bp, Structures.Palette p, int era) {
		int floors = 3;
		bp.hollow(0, 1, 0, 11, 40, 11);
		bp.fill(0, 0, 0, 11, 0, 11, b(Blocks.SMOOTH_STONE));
		int roofY = body(bp, p, 4, 1, 1, 10, 10, floors, true);
		bp.door(5, 1, 1, Blocks.IRON_DOOR, Direction.NORTH);
		bp.door(6, 1, 1, Blocks.IRON_DOOR, Direction.NORTH);
		// sawtooth glass roof
		bp.fill(1, roofY, 1, 10, roofY, 10, b(Blocks.SMOOTH_STONE_SLAB));
		for (int z = 2; z <= 9; z += 3) bp.fill(1, roofY + 1, z, 10, roofY + 1, z, Blocks.GLASS);
		// four brick smokestacks, twenty blocks high, each breathing steam and smoke
		int[][] stacks = {{2, 2}, {2, 9}, {9, 2}, {9, 9}};
		int k = 0;
		for (int[] s : stacks) {
			int top = roofY + 12 + (k++ % 2) * 4;
			bp.fill(s[0], roofY, s[1], s[0], top, s[1], Blocks.BRICKS.defaultBlockState());
			bp.set(s[0], top + 1, s[1], Blocks.CAMPFIRE);
			bp.set(s[0], top, s[1], Blocks.HAY_BLOCK);
			bp.emit(s[0], top + 2, s[1], k % 2 == 0 ? 2 : 1);
		}
		bp.set(3, 1, 3, Blocks.BLAST_FURNACE).set(4, 1, 3, Blocks.BLAST_FURNACE).set(7, 1, 3, Blocks.PISTON).set(8, 1, 3, Blocks.OBSERVER);
		bp.set(3, 1, 7, Blocks.HOPPER).set(4, 1, 7, Blocks.CHEST).set(5, 3, 5, Blocks.LANTERN).set(7, 1, 7, Blocks.ANVIL);
	}

	static void foundry(Blueprint bp, Structures.Palette p, int era) {
		int roofY = body(bp, p, Math.max(era, 3), 1, 2, 10, 9, 2, false);
		bp.fill(1, roofY, 2, 10, roofY, 9, b(Blocks.SMOOTH_STONE_SLAB));
		bp.set(3, 1, 4, Blocks.BLAST_FURNACE).set(4, 1, 4, Blocks.BLAST_FURNACE).set(3, 1, 6, Blocks.FURNACE).set(7, 1, 5, Blocks.ANVIL).set(8, 1, 7, Blocks.CAULDRON);
		bp.set(6, 1, 4, Blocks.LAVA_CAULDRON);
		// a tall blast-furnace stack
		bp.fill(8, roofY, 8, 9, roofY + 10, 9, b(Blocks.BRICKS));
		bp.hollow(8, roofY + 1, 8, 8, roofY + 9, 8);
		bp.set(8, roofY + 11, 8, Blocks.CAMPFIRE).set(9, roofY + 11, 9, Blocks.CAMPFIRE);
		bp.emit(8, roofY + 12, 8, 1).emit(9, roofY + 12, 9, 3);
		chimney(bp, 2, 3, roofY + 6, true, 1);
	}

	static void sawmill(Blueprint bp, Structures.Palette p, int era) {
		bp.hollow(1, 1, 1, 10, 9, 10);
		bp.fill(1, 0, 1, 10, 0, 10, b(Blocks.PODZOL));
		bp.corners(2, 2, 9, 7, 1, 4, mats(Math.max(1, era)).trim());
		bp.roof(1, 1, 10, 8, 5, p.roof(), p.roofTop());
		bp.set(4, 1, 4, Blocks.STONECUTTER).set(6, 1, 4, Blocks.CRAFTING_TABLE).set(5, 1, 6, Blocks.BARREL);
		for (int x = 2; x <= 9; x++) for (int y = 1; y <= 2; y++) bp.log(x, y, 9, Blocks.OAK_LOG, Direction.Axis.X);
		bp.fill(9, 1, 3, 9, 3, 5, b(Blocks.SPRUCE_PLANKS));
		bp.set(10, 1, 4, Blocks.WATER); // the mill race
		bp.set(10, 2, 4, Blocks.OAK_TRAPDOOR);
	}

	static void windmill(Blueprint bp, Structures.Palette p, int era) {
		bp.hollow(1, 1, 1, 10, 22, 10);
		bp.fill(1, 0, 1, 10, 0, 10, b(Blocks.PODZOL));
		BlockState wall = era >= 2 ? b(Blocks.STONE_BRICKS) : b(Blocks.WHITE_TERRACOTTA);
		// a round-ish tower tapering upward
		for (int y = 1; y <= 12; y++) {
			int inset = y / 5;
			bp.walls(3 + inset, 3 + inset, 8 - inset, 8 - inset, y, y, wall);
		}
		bp.door(5, 1, 3, p.door(), Direction.NORTH);
		bp.ladder(6, 1, 12, 7, Direction.NORTH);
		bp.fill(5, 13, 5, 6, 13, 6, p.roofTop());
		bp.fill(5, 14, 5, 6, 14, 6, p.roofTop());
		// four sails on a hub
		bp.set(5, 12, 2, Blocks.OAK_FENCE).set(6, 12, 2, Blocks.OAK_FENCE);
		for (int i = 1; i <= 5; i++) {
			bp.set(5, 12 + i, 2, Blocks.OAK_FENCE).set(5, 12 - i, 2, Blocks.OAK_FENCE);
			bp.set(5 + i, 12, 2, Blocks.OAK_FENCE).set(5 - i, 12, 2, Blocks.OAK_FENCE);
			if (i >= 2) {
				bp.set(5 + 1, 12 + i, 2, Blocks.WHITE_WOOL).set(5 - 1, 12 - i, 2, Blocks.WHITE_WOOL);
				bp.set(5 + i, 12 - 1, 2, Blocks.WHITE_WOOL).set(5 - i, 12 + 1, 2, Blocks.WHITE_WOOL);
			}
		}
		bp.set(1, 1, 1, Blocks.HAY_BLOCK).set(10, 1, 1, Blocks.HAY_BLOCK).set(10, 1, 10, Blocks.HAY_BLOCK);
	}

	static void university(Blueprint bp, Structures.Palette p, int era) {
		int roofY = body(bp, p, 4, 1, 2, 10, 9, 4, true);
		bp.roof(0, 1, 11, 10, roofY, p.roof(), p.roofTop());
		// a copper-roofed dome-like lantern on the ridge
		bp.fill(4, roofY + 3, 4, 7, roofY + 7, 7, b(Blocks.QUARTZ_BRICKS));
		bp.hollow(5, roofY + 4, 5, 6, roofY + 6, 6);
		bp.set(4, roofY + 5, 5, Blocks.GLASS_PANE).set(7, roofY + 5, 6, Blocks.GLASS_PANE);
		bp.fill(5, roofY + 8, 5, 6, roofY + 8, 6, b(Blocks.OXIDIZED_COPPER));
		bp.set(5, roofY + 9, 5, Blocks.LIGHTNING_ROD);
		for (int f = 0; f < 4; f++) {
			int y = 1 + f * 3;
			for (int x = 3; x <= 8; x++) bp.set(x, y, 8, Blocks.BOOKSHELF);
		}
		bp.set(5, 1, 5, Blocks.LECTERN).set(6, 1, 5, Blocks.ENCHANTING_TABLE).set(5, 3, 5, Blocks.LANTERN);
	}

	static void station(Blueprint bp, Structures.Palette p, int era) {
		bp.hollow(0, 1, 0, 11, 24, 11);
		bp.fill(0, 0, 0, 11, 0, 11, b(Blocks.SMOOTH_STONE));
		// the hall
		int roofY = body(bp, p, 4, 1, 1, 10, 5, 2, true);
		bp.fill(1, roofY, 1, 10, roofY, 5, b(Blocks.SMOOTH_STONE_SLAB));
		// a clock tower
		bp.fill(1, roofY, 1, 3, roofY + 8, 3, b(Blocks.BRICKS));
		bp.set(2, roofY + 5, 1, Blocks.WHITE_CONCRETE).set(1, roofY + 5, 2, Blocks.WHITE_CONCRETE);
		bp.fill(1, roofY + 9, 1, 3, roofY + 9, 3, p.roofTop());
		bp.set(2, roofY + 10, 2, Blocks.LIGHTNING_ROD);
		// the platform, a canopy on iron posts, and the track
		bp.fill(0, 1, 6, 11, 1, 8, b(Blocks.STONE_BRICKS));
		for (int x = 1; x <= 10; x += 3) bp.fill(x, 2, 6, x, 5, 6, b(Blocks.IRON_BARS));
		bp.fill(0, 6, 6, 11, 6, 8, b(Blocks.SMOOTH_STONE_SLAB));
		for (int x = 0; x <= 11; x++) bp.set(x, 1, 10, Blocks.GRAVEL).set(x, 2, 10, Blocks.RAIL.defaultBlockState().setValue(net.minecraft.world.level.block.RailBlock.SHAPE, net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST));
		bp.set(1, 2, 7, Blocks.LANTERN).set(5, 2, 7, Blocks.LANTERN).set(9, 2, 7, Blocks.LANTERN).set(8, 2, 7, Blocks.BARREL);
		bp.emit(9, 3, 10, 2);
	}

	private Architecture() {}
}
