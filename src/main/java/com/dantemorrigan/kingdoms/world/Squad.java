package com.dantemorrigan.kingdoms.world;

import com.dantemorrigan.kingdoms.sim.Army;
import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * The brain of an army that is standing in the world: a commander leads the column, the men keep their places behind
 * and beside him and run to catch up. At an enemy town the army ranks itself around the walls, its sappers plant
 * dynamite against the wall (and withdraw), and when the wall is down every man storms in. A column that meets water
 * lays a pontoon bridge; one that meets a ledge or trees cuts through. Nothing here waits for the simulation: the
 * commander's position is the army's position while somebody watches.
 */
final class Squad {
	enum Mode { MARCH, ENCIRCLE, BREACH, ASSAULT }

	/** Temporary pontoon planks to take away again: {x, y, z, expiry tick}. */
	static final List<long[]> TEMP = new ArrayList<>();

	final int armyId;
	Mode mode = Mode.MARCH;
	CitizenEntity commander;
	double hx, hz = 1;
	double lastX, lastZ;
	long lastCheck, modeSince, tntAt;
	int stuck, attempts;
	int breachCol = -1;
	final List<int[]> bridge = new ArrayList<>();
	int enemy = -1;
	long musterUntil;
	boolean started;

	Squad(int armyId) {
		this.armyId = armyId;
	}

	static void go(CitizenEntity c, double x, double y, double z, double speed) {
		var nav = c.getNavigation();
		BlockPos dest = nav.getTargetPos();
		if (dest == null || nav.isDone() || dest.distSqr(BlockPos.containing(x, y, z)) > 6) nav.moveTo(x, y, z, speed);
		else nav.setSpeedModifier(speed);
		c.setSprinting(speed > 1.15);
	}

	static int groundY(ServerLevel level, double x, double z, double fallback) {
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
		if (!level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) return (int) fallback;
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
	}

	static void face(CitizenEntity c, double dx, double dz) {
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		c.setYRot(yaw);
		c.setYHeadRot(yaw);
		c.setYBodyRot(yaw);
	}

	void step(ServerLevel level, Realm r, Army a, Kingdom k, List<CitizenEntity> men) {
		if (men.isEmpty()) return;
		long now = level.getGameTime();
		if (!started) {
			started = true;
			if (a.mustering) musterUntil = now + 20 * 20; // the ranks stand in the yard a while before marching out
		}
		if (commander == null || !commander.isAlive() || commander.isRemoved() || !men.contains(commander)) promote(men, k);
		a.x = (float) ((commander.getX() - r.originX) / Realm.CELL);
		a.z = (float) ((commander.getZ() - r.originZ) / Realm.CELL);

		if (now < musterUntil) {
			// formed up in the yard: ranks facing the same way, the commander in front
			double dx = commander.getX() - men.stream().filter(m -> m != commander).mapToDouble(CitizenEntity::getX).average().orElse(commander.getX());
			double dz = commander.getZ() - men.stream().filter(m -> m != commander).mapToDouble(CitizenEntity::getZ).average().orElse(commander.getZ() - 1);
			double l = Math.hypot(dx, dz);
			if (l > 0.1) {
				hx = dx / l;
				hz = dz / l;
			}
			for (CitizenEntity m : men) if (m.getTarget() == null) {
				m.getNavigation().stop();
				m.setSprinting(false);
				face(m, hx, hz);
			}
			return;
		}
		a.mustering = false;
		// what are we doing: besieging an enemy town, or on the road?
		Settlement town = null;
		boolean sieging = false;
		int cx = (int) Math.floor(a.tx), cz = (int) Math.floor(a.tz);
		if (a.mission == 0 && !a.home && Realm.inside(cx, cz) && Math.hypot(a.tx - a.x, a.tz - a.z) < 1.1) {
			int holder = r.owner[Realm.idx(cx, cz)];
			if (holder != 0 && holder != k.id && k.wars.contains(holder)) {
				sieging = true;
				town = r.settlementAtCell(cx, cz);
				enemy = holder;
			}
		}
		if (!sieging && mode != Mode.MARCH) mode = Mode.MARCH;

		List<CitizenEntity> followers = new ArrayList<>(men);
		followers.remove(commander);
		if (sieging) siege(level, r, a, k, town, followers, now);
		else march(level, r, a, followers, now);
		a.pressure = sieging ? pressure(level, town, men) : 1f;
		unstick(level, r, a, followers, now);
		flushBridge(level);
	}

	void promote(List<CitizenEntity> men, Kingdom k) {
		CitizenEntity best = null;
		for (CitizenEntity c : men) if (c.isAlive() && (best == null || (!c.sapper && c.getMaxHealth() > best.getMaxHealth()))) best = c;
		commander = best;
		if (best != null) best.makeCommander(k);
	}

	// ------------------------------------------------------------------ marching

	void march(ServerLevel level, Realm r, Army a, List<CitizenEntity> followers, long now) {
		double wx = commander.getX(), wz = commander.getZ();
		boolean haveWaypoint = false;
		if (a.path != null && a.pi < a.path.length) {
			int cell = a.path[Math.min(a.pi, a.path.length - 1)];
			boolean last = a.pi >= a.path.length - 1;
			wx = last ? r.originX + a.tx * Realm.CELL : r.originX + (cell % Realm.N) * Realm.CELL + Realm.CELL / 2.0;
			wz = last ? r.originZ + a.tz * Realm.CELL : r.originZ + (cell / Realm.N) * Realm.CELL + Realm.CELL / 2.0;
			haveWaypoint = true;
			if (!last && Math.hypot(wx - commander.getX(), wz - commander.getZ()) < 9) a.pi++;
		} else if (Math.hypot(a.tx - a.x, a.tz - a.z) > 0.2) {
			wx = r.originX + a.tx * Realm.CELL;
			wz = r.originZ + a.tz * Realm.CELL;
			haveWaypoint = true;
		}
		double dx = wx - commander.getX(), dz = wz - commander.getZ(), d = Math.hypot(dx, dz);
		if (haveWaypoint && d > 2.5) {
			hx += (dx / d - hx) * 0.35;
			hz += (dz / d - hz) * 0.35;
			double hl = Math.hypot(hx, hz);
			if (hl > 1e-3) {
				hx /= hl;
				hz /= hl;
			}
			// the column keeps together: the commander slows if the men fall far behind
			double lag = 0;
			for (CitizenEntity m : followers) lag = Math.max(lag, m.distanceTo(commander));
			double speed = lag > 18 ? 0.8 : 1.3;
			if (commander.getTarget() == null) go(commander, wx, groundY(level, wx, wz, commander.getY()), wz, speed);
		} else if (commander.getTarget() == null) {
			commander.getNavigation().stop();
			commander.setSprinting(false);
		}
		double rx = -hz, rz = hx;
		for (int i = 0; i < followers.size(); i++) {
			CitizenEntity m = followers.get(i);
			int row = i / 6, col = i % 6;
			double side = (col - 2.5) * 1.6, back = 2.2 + row * 1.9;
			double sx = commander.getX() - hx * back + rx * side, sz = commander.getZ() - hz * back + rz * side;
			order(level, m, sx, sz, hx, hz, commander.getY());
		}
	}

	/** A man goes to his place: runs when far, walks when near, and stands facing the way of the column when there. */
	static void order(ServerLevel level, CitizenEntity m, double sx, double sz, double fx, double fz, double fallbackY) {
		if (m.getTarget() != null) return;
		double d = Math.hypot(m.getX() - sx, m.getZ() - sz);
		if (d < 1.4) {
			m.getNavigation().stop();
			m.setSprinting(false);
			face(m, fx, fz);
			return;
		}
		go(m, sx, groundY(level, sx, sz, fallbackY), sz, d > 12 ? 1.5 : d > 5 ? 1.3 : 1.0);
	}

	// ------------------------------------------------------------------ siege

	void siege(ServerLevel level, Realm r, Army a, Kingdom k, Settlement town, List<CitizenEntity> men, long now) {
		double cxs = town != null ? town.x : r.originX + (Math.floor(a.tx) + 0.5) * Realm.CELL;
		double czs = town != null ? town.z : r.originZ + (Math.floor(a.tz) + 0.5) * Realm.CELL;
		boolean walled = town != null && town.wall > 0 && town.wallProgress >= 0.6f;
		if (mode == Mode.MARCH) {
			mode = walled ? Mode.ENCIRCLE : Mode.ASSAULT;
			modeSince = now;
			breachCol = -1;
			attempts = 0;
		}
		int wallR = town != null ? town.wallR : 12;
		switch (mode) {
			case ENCIRCLE -> {
				// the ring closes around the walls; the commander waits at the point of attack
				int col = pickBreach(town, commander.getX(), commander.getZ());
				breachCol = col;
				double R = wallR + 12;
				int n = men.size();
				for (int i = 0; i < n; i++) {
					CitizenEntity m = men.get(i);
					double ang = Math.PI * 2 * i / Math.max(1, n);
					double sx = cxs + Math.max(-R, Math.min(R, R * 1.45 * Math.cos(ang))), sz = czs + Math.max(-R, Math.min(R, R * 1.45 * Math.sin(ang)));
					order(level, m, sx, sz, cxs - sx, czs - sz, commander.getY());
				}
				if (col >= 0) {
					int[] outer = town.wallPos(col, 0);
					int[] nrm = normal(town, col);
					double px = outer[0] + nrm[0] * 9, pz = outer[1] + nrm[1] * 9;
					go(commander, px, groundY(level, px, pz, commander.getY()), pz, 1.3);
					face(commander, cxs - commander.getX(), czs - commander.getZ());
				}
				if (now - modeSince > 20 * 18 && col >= 0) {
					mode = Mode.BREACH;
					modeSince = now;
					tntAt = 0;
				}
			}
			case BREACH -> breach(level, r, a, k, town, men, now, cxs, czs);
			default -> {
				assault(level, a, men, cxs, czs, now);
				batterBuildings(level, r, town, k, men, now);
			}
		}
	}

	/** The wall column nearest to a point, not under a gate house, that is really there. */
	static int pickBreach(Settlement s, double x, double z) {
		int best = -1;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < s.wallLen(); i += 2) {
			if (s.gate(i) || !s.wallBit(i)) continue;
			int[] p = s.wallPos(i, 0);
			double d = Math.hypot(p[0] - x, p[1] - z);
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	static int[] normal(Settlement s, int col) {
		return switch (s.wallSide(col)) {
			case 0 -> new int[]{0, -1};
			case 1 -> new int[]{1, 0};
			case 2 -> new int[]{0, 1};
			default -> new int[]{-1, 0};
		};
	}

	/** Sappers lay charges against the wall, everyone backs away, the charges go off, and the gap is checked. */
	void breach(ServerLevel level, Realm r, Army a, Kingdom k, Settlement town, List<CitizenEntity> men, long now, double cxs, double czs) {
		int col = breachCol;
		if (col < 0 || attempts >= 3) {
			mode = Mode.ASSAULT;
			modeSince = now;
			return;
		}
		int[] outer = town.wallPos(col, 0), nrm = normal(town, col);
		// keep the ring, but back away from the blast
		for (int i = 0; i < men.size(); i++) {
			CitizenEntity m = men.get(i);
			if (m.sapper) continue;
			double d = Math.hypot(m.getX() - outer[0], m.getZ() - outer[1]);
			if (d < 20 && m.getTarget() == null) {
				double px = outer[0] + nrm[0] * 20 + (i % 5 - 2) * 3 * -nrm[1], pz = outer[1] + nrm[1] * 20 + (i % 5 - 2) * 3 * nrm[0];
				go(m, px, groundY(level, px, pz, m.getY()), pz, 1.4);
			}
		}
		List<CitizenEntity> sappers = new ArrayList<>();
		for (CitizenEntity m : men) if (m.sapper) sappers.add(m);
		if (commander.sapper) sappers.add(commander);
		if (tntAt == 0) {
			// walk up to the wall and plant
			boolean near = true;
			for (CitizenEntity m : sappers) {
				double px = outer[0] + nrm[0] * 2.0, pz = outer[1] + nrm[1] * 2.0;
				if (Math.hypot(m.getX() - px, m.getZ() - pz) > 5) {
					near = false;
					go(m, px, groundY(level, px, pz, m.getY()), pz, 1.4);
				}
			}
			if (sappers.isEmpty()) near = now - modeSince > 100;
			if (near || now - modeSince > 20 * 25) {
				plant(level, town, col, nrm);
				tntAt = now;
				for (CitizenEntity m : sappers) {
					double px = outer[0] + nrm[0] * 22, pz = outer[1] + nrm[1] * 22;
					go(m, px, groundY(level, px, pz, m.getY()), pz, 1.6);
				}
			}
			return;
		}
		if (now - tntAt < 90) return; // the fuse is burning
		attempts++;
		tntAt = 0;
		int gy = groundYWall(level, outer[0], outer[1]);
		boolean open = level.getBlockState(new BlockPos(outer[0], gy + 2, outer[1])).isAir();
		if (open) {
			WallWorks.damaged(town, col, r.day);
			mode = Mode.ASSAULT;
			modeSince = now;
			r.log(1, outer[0], outer[1], "kingdoms.log.breach", com.dantemorrigan.kingdoms.sim.Chronicle.kingdom(k), com.dantemorrigan.kingdoms.sim.Chronicle.name(town.name));
		} else {
			// a thick wall: lay another charge a little to the side
			breachCol = (col + (attempts % 2 == 0 ? 3 : -3) + town.wallLen()) % town.wallLen();
			if (town.gate(breachCol)) breachCol = col;
		}
	}

	static int groundYWall(ServerLevel level, int x, int z) {
		int[] sf = WallWorks.surface(level, x, z);
		return sf == null ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : sf[0];
	}

	/** Three charges at the foot of the wall: primed TNT, the real thing. */
	void plant(ServerLevel level, Settlement town, int col, int[] nrm) {
		for (int k = -1; k <= 1; k++) {
			int c = (col + k * 2 + town.wallLen()) % town.wallLen();
			int[] p = town.wallPos(c, 0);
			double x = p[0] + 0.5 + nrm[0] * 0.9, z = p[1] + 0.5 + nrm[1] * 0.9;
			int y = groundYWall(level, p[0], p[1]) + 1;
			PrimedTnt tnt = new PrimedTnt(level, x, y, z, null);
			tnt.setFuse(70 + Math.abs(k) * 4);
			level.addFreshEntity(tnt);
		}
		level.playSound(null, new BlockPos(town.wallPos(col, 0)[0], 70, town.wallPos(col, 0)[1]), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.5f, 1f);
	}

	/** Everyone runs for the middle of the town; their own fighting instincts do the rest. */
	void assault(ServerLevel level, Army a, List<CitizenEntity> men, double cx, double cz, long now) {
		double ty = groundY(level, cx, cz, commander.getY());
		for (CitizenEntity m : men) {
			if (m.getTarget() != null) continue;
			go(m, cx + (m.getId() % 5 - 2) * 2, ty, cz + (m.getId() % 3 - 1) * 2, 1.5);
		}
		if (commander.getTarget() == null) go(commander, cx, ty, cz, 1.35);
	}

	void batterBuildings(ServerLevel level, Realm r, Settlement town, Kingdom k, List<CitizenEntity> men, long now) {
		if (town != null && now % 60 == 0) WarZone.batter(level, r, town, k, men, 60);
	}

	float pressure(ServerLevel level, Settlement town, List<CitizenEntity> men) {
		if (mode == Mode.ASSAULT) return 1f;
		if (town == null) return 0.5f;
		int near = 0;
		for (CitizenEntity m : men) if (Math.hypot(m.getX() - town.x, m.getZ() - town.z) < town.wallR + 24) near++;
		return Math.min(0.6f, near / (float) men.size());
	}

	// ------------------------------------------------------------------ obstacles

	/** A commander who does not get anywhere: bridge the water ahead, or cut through what stands in the way. */
	void unstick(ServerLevel level, Realm r, Army a, List<CitizenEntity> men, long now) {
		if (mode != Mode.MARCH || now - lastCheck < 40) return;
		double moved = Math.hypot(commander.getX() - lastX, commander.getZ() - lastZ);
		lastX = commander.getX();
		lastZ = commander.getZ();
		lastCheck = now;
		boolean wants = a.path != null && !arrivedAt(a) && commander.getTarget() == null;
		if (!wants || moved > 1.6) {
			stuck = 0;
			return;
		}
		stuck++;
		if (stuck < 2) return;
		double len = Math.hypot(hx, hz);
		if (len < 1e-3) return;
		double dx = hx / len, dz = hz / len;
		// water ahead?
		boolean water = false;
		for (int k = 2; k <= 10 && !water; k++) {
			int px = (int) Math.floor(commander.getX() + dx * k), pz = (int) Math.floor(commander.getZ() + dz * k);
			if (!level.getChunkSource().hasChunk(px >> 4, pz >> 4)) continue;
			int[] sf = WallWorks.surface(level, px, pz);
			if (sf != null && sf[0] != sf[1]) water = true;
		}
		if (water) {
			layBridge(level, now, commander.getX(), commander.getZ(), dx, dz);
			level.playSound(null, commander.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1f, 1f);
		} else {
			cut(level, commander.getX(), commander.getY(), commander.getZ(), dx, dz);
		}
		if (stuck >= 5) { // last resort: the commander is carried over the obstacle, and the column follows
			double nx = commander.getX() + dx * 7, nz = commander.getZ() + dz * 7;
			commander.teleportTo(nx, groundY(level, nx, nz, commander.getY()), nz);
			stuck = 0;
		}
	}

	static boolean arrivedAt(Army a) {
		return Math.hypot(a.tx - a.x, a.tz - a.z) < 0.35;
	}

	/** A two-wide pontoon of planks on the water, in a straight line to the far shore; it is taken away again later. */
	void layBridge(ServerLevel level, long now, double x, double z, double dx, double dz) {
		bridge.clear();
		boolean seenWater = false;
		for (int k = 1; k <= 26; k++) {
			int px = (int) Math.floor(x + dx * k), pz = (int) Math.floor(z + dz * k);
			if (!level.getChunkSource().hasChunk(px >> 4, pz >> 4)) break;
			int[] sf = WallWorks.surface(level, px, pz);
			if (sf == null) break;
			boolean wet = sf[0] != sf[1];
			if (wet) {
				seenWater = true;
				bridge.add(new int[]{px, sf[0], pz});
				bridge.add(new int[]{px + (int) Math.round(-dz), sf[0], pz + (int) Math.round(dx)});
			} else if (seenWater) break;
		}
	}

	void flushBridge(ServerLevel level) {
		for (int n = 0; n < 4 && !bridge.isEmpty(); n++) {
			int[] p = bridge.remove(0);
			BlockPos pos = new BlockPos(p[0], p[1], p[2]);
			if (!level.getBlockState(pos).getFluidState().isEmpty()) {
				level.setBlock(pos, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
				TEMP.add(new long[]{p[0], p[1], p[2], level.getGameTime() + 20 * 60 * 6});
				if (level.getRandom().nextInt(3) == 0) level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8f, 1f);
			}
		}
	}

	static void expireBridges(ServerLevel level) {
		long t = level.getGameTime();
		TEMP.removeIf(e -> {
			if (e[3] > t) return false;
			BlockPos pos = new BlockPos((int) e[0], (int) e[1], (int) e[2]);
			if (level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4) && level.getBlockState(pos).is(Blocks.OAK_PLANKS)) {
				level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
			}
			return true;
		});
	}

	/** Axes and spades: the trees, bushes and earth directly ahead are cleared so the column can pass. */
	void cut(ServerLevel level, double x, double y, double z, double dx, double dz) {
		for (int k = 1; k <= 3; k++) {
			for (int h = 0; h <= 2; h++) {
				BlockPos p = BlockPos.containing(x + dx * k, y + h, z + dz * k);
				var st = level.getBlockState(p);
				if (st.isAir() || !st.getFluidState().isEmpty() || st.getDestroySpeed(level, p) < 0 || st.getDestroySpeed(level, p) > 2.5f) continue;
				level.destroyBlock(p, false);
				level.sendParticles(ParticleTypes.POOF, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.01);
			}
		}
	}
}
