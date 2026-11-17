package com.dantemorrigan.kingdoms.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A structure as a list of blocks relative to the plot corner (x, z in 0..11) and the plot's ground level (y = 0 is
 * the floor). Built bottom-up, so in the world it visibly grows layer by layer.
 */
public final class Blueprint {
	public record Entry(int x, int y, int z, BlockState state, boolean flag) {}

	private final Map<Long, Entry> map = new LinkedHashMap<>();
	private List<Entry> sorted;

	private static long key(int x, int y, int z) {
		return ((long) (y + 512) << 20) | ((long) (x & 0x3FF) << 10) | (z & 0x3FF);
	}

	public Blueprint set(int x, int y, int z, BlockState s) {
		map.put(key(x, y, z), new Entry(x, y, z, s, false));
		sorted = null;
		return this;
	}

	public Blueprint set(int x, int y, int z, Block b) {
		return set(x, y, z, b.defaultBlockState());
	}

	public Blueprint flagCloth(int x, int y, int z, BlockState s) {
		map.put(key(x, y, z), new Entry(x, y, z, s, true));
		sorted = null;
		return this;
	}

	public Blueprint fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState s) {
		for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
			for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
				for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, s);
		return this;
	}

	public Blueprint fill(int x1, int y1, int z1, int x2, int y2, int z2, Block b) {
		return fill(x1, y1, z1, x2, y2, z2, b.defaultBlockState());
	}

	/** Hollow rectangle of walls from y1 to y2. */
	public Blueprint walls(int x1, int z1, int x2, int z2, int y1, int y2, BlockState s) {
		for (int y = y1; y <= y2; y++) {
			for (int x = x1; x <= x2; x++) {
				set(x, y, z1, s);
				set(x, y, z2, s);
			}
			for (int z = z1; z <= z2; z++) {
				set(x1, y, z, s);
				set(x2, y, z, s);
			}
		}
		return this;
	}

	/** Air inside a box (clears what the terrain left there). */
	public Blueprint hollow(int x1, int y1, int z1, int x2, int y2, int z2) {
		return fill(x1, y1, z1, x2, y2, z2, Blocks.AIR.defaultBlockState());
	}

	public Blueprint corners(int x1, int z1, int x2, int z2, int y1, int y2, BlockState s) {
		for (int y = y1; y <= y2; y++) {
			set(x1, y, z1, s);
			set(x2, y, z1, s);
			set(x1, y, z2, s);
			set(x2, y, z2, s);
		}
		return this;
	}

	/** Wall material used to close gables. */
	public BlockState gableWall = Blocks.OAK_PLANKS.defaultBlockState();

	private static BlockState stair(BlockState base, Direction facing) {
		return base.setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, Half.BOTTOM);
	}

	private static BlockState full(BlockState slab) {
		return slab.setValue(SlabBlock.TYPE, SlabType.DOUBLE);
	}

	/**
	 * A real pitched roof of stairs, one overhanging block around (x1,z1)-(x2,z2): a gable along the longer side with
	 * closed gable ends, or a pyramid for a nearly square footprint. {@code stairs} / {@code slab} come from the palette.
	 */
	public Blueprint roof(int x1, int z1, int x2, int z2, int y, BlockState stairs, BlockState slab) {
		int dx = x2 - x1, dz = z2 - z1;
		if (dx <= dz) { // ridge along Z, slopes across X
			for (int i = 0; ; i++) {
				int xl = x1 + i, xr = x2 - i;
				if (xl > xr) break;
				if (xl == xr) {
					fill(xl, y + i, z1, xl, y + i, z2, full(slab));
					break;
				}
				fill(xl, y + i, z1, xl, y + i, z2, stair(stairs, Direction.WEST));
				fill(xr, y + i, z1, xr, y + i, z2, stair(stairs, Direction.EAST));
				if (xr - xl > 1) {
					fill(xl + 1, y + i, z1 + 1, xr - 1, y + i, z1 + 1, gableWall);
					fill(xl + 1, y + i, z2 - 1, xr - 1, y + i, z2 - 1, gableWall);
				}
			}
		} else { // ridge along X, slopes across Z
			for (int i = 0; ; i++) {
				int zl = z1 + i, zr = z2 - i;
				if (zl > zr) break;
				if (zl == zr) {
					fill(x1, y + i, zl, x2, y + i, zl, full(slab));
					break;
				}
				fill(x1, y + i, zl, x2, y + i, zl, stair(stairs, Direction.NORTH));
				fill(x1, y + i, zr, x2, y + i, zr, stair(stairs, Direction.SOUTH));
				if (zr - zl > 1) {
					fill(x1 + 1, y + i, zl + 1, x1 + 1, y + i, zr - 1, gableWall);
					fill(x2 - 1, y + i, zl + 1, x2 - 1, y + i, zr - 1, gableWall);
				}
			}
		}
		return this;
	}

	private Blueprint pyramid(int x1, int z1, int x2, int z2, int y, BlockState stairs, BlockState slab) {
		for (int i = 0; x1 + i <= x2 - i && z1 + i <= z2 - i; i++) {
			int xl = x1 + i, xr = x2 - i, zl = z1 + i, zr = z2 - i;
			if (xr - xl < 2 || zr - zl < 2) {
				fill(xl, y + i, zl, xr, y + i, zr, full(slab));
				break;
			}
			for (int x = xl + 1; x < xr; x++) {
				set(x, y + i, zl, stair(stairs, Direction.NORTH));
				set(x, y + i, zr, stair(stairs, Direction.SOUTH));
			}
			for (int z = zl + 1; z < zr; z++) {
				set(xl, y + i, z, stair(stairs, Direction.WEST));
				set(xr, y + i, z, stair(stairs, Direction.EAST));
			}
			set(xl, y + i, zl, full(slab));
			set(xr, y + i, zl, full(slab));
			set(xl, y + i, zr, full(slab));
			set(xr, y + i, zr, full(slab));
		}
		return this;
	}

	/** Crenellations on top of a wall ring. */
	public Blueprint battlements(int x1, int z1, int x2, int z2, int y, BlockState s) {
		for (int x = x1; x <= x2; x++) {
			if ((x - x1) % 2 == 0) {
				set(x, y, z1, s);
				set(x, y, z2, s);
			}
		}
		for (int z = z1; z <= z2; z++) {
			if ((z - z1) % 2 == 0) {
				set(x1, y, z, s);
				set(x2, y, z, s);
			}
		}
		return this;
	}

	public Blueprint door(int x, int y, int z, Block door, Direction facing) {
		BlockState lower = door.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
		set(x, y, z, lower);
		set(x, y + 1, z, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		return this;
	}

	public Blueprint gate(int x, int y, int z, Direction facing) {
		return set(x, y, z, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, facing));
	}

	/** Ladder against the block on the side opposite to {@code facing}. */
	public Blueprint ladder(int x, int y1, int y2, int z, Direction facing) {
		for (int y = y1; y <= y2; y++) set(x, y, z, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, facing));
		return this;
	}

	public Blueprint log(int x, int y, int z, Block log, Direction.Axis axis) {
		return set(x, y, z, log.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis));
	}

	/** A pole with a two-by-two banner in the kingdom's color. */
	public Blueprint flag(int x, int y, int z, BlockState cloth, boolean towardEast) {
		fill(x, y, z, x, y + 3, z, Blocks.OAK_FENCE.defaultBlockState());
		int d = towardEast ? 1 : -1;
		flagCloth(x + d, y + 2, z, cloth);
		flagCloth(x + d, y + 3, z, cloth);
		flagCloth(x + 2 * d, y + 2, z, cloth);
		flagCloth(x + 2 * d, y + 3, z, cloth);
		return this;
	}

	public List<Entry> entries() {
		if (sorted == null) {
			sorted = new ArrayList<>(map.values());
			// Clearing first, then bottom-up; inside a layer from the outside in.
			sorted.sort(Comparator.<Entry>comparingInt(e -> e.state.isAir() ? 0 : 1)
					.thenComparingInt(e -> e.y)
					.thenComparingInt(e -> -Math.max(Math.abs(e.x * 2 - 11), Math.abs(e.z * 2 - 11))));
		}
		return sorted;
	}

	public int size() {
		return map.size();
	}
}
