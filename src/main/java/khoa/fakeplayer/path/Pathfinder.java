package khoa.fakeplayer.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * A* over "feet positions". A position is valid when the body fits (2 free blocks) and there is ground, water or a
 * ladder. Moves: walk, diagonal (no corner cutting), jump up one block, drop down (safe fall), swim up/down and
 * climb ladders. The cheapest route is found, so the bot walks around lava, walls and big drops instead of taking
 * the straight line. Closed wooden doors and gates are allowed at an extra cost (the bot opens them).
 */
public final class Pathfinder {
	public enum Move { START, WALK, DIAGONAL, ASCEND, DESCEND, SWIM_UP, SWIM_DOWN, CLIMB_UP, CLIMB_DOWN }

	public record Node(BlockPos pos, Move move, boolean door) {
	}

	public record Result(List<Node> nodes, boolean reachedGoal, int visited, double cost) {
		public boolean isEmpty() {
			return this.nodes.size() <= 1;
		}
	}

	private static final int MAX_FALL = 3;
	private static final int MAX_FALL_INTO_WATER = 20;

	private record Open(BlockPos pos, double g, double f) {
	}

	private static final class Info {
		BlockPos parent;
		Move move = Move.START;
		boolean door;
		double g = Double.MAX_VALUE;
	}

	private static final class Out {
		final List<BlockPos> pos = new ArrayList<>(24);
		final List<Move> move = new ArrayList<>(24);
		final List<Double> cost = new ArrayList<>(24);
		final List<Boolean> door = new ArrayList<>(24);

		void clear() {
			this.pos.clear();
			this.move.clear();
			this.cost.clear();
			this.door.clear();
		}

		void add(BlockPos p, Move m, double c, boolean d) {
			this.pos.add(p);
			this.move.add(m);
			this.cost.add(c);
			this.door.add(d);
		}
	}

	private Pathfinder() {
	}

	/** Snaps an arbitrary target to the nearest sensible standing position (down to ground, or up out of a block). */
	public static BlockPos snapGoal(Level level, BlockPos goal) {
		Terrain t = new Terrain(level);
		if (t.valid(goal)) {
			return goal;
		}
		for (int dy = 1; dy <= 8; dy++) {
			if (t.valid(goal.below(dy))) {
				return goal.below(dy);
			}
		}
		for (int dy = 1; dy <= 3; dy++) {
			if (t.valid(goal.above(dy))) {
				return goal.above(dy);
			}
		}
		return goal;
	}

	public static Result find(Level level, BlockPos start, BlockPos goal, int maxNodes) {
		Terrain t = new Terrain(level);
		Map<Long, Info> info = new HashMap<>();
		PriorityQueue<Open> open = new PriorityQueue<>(Comparator.comparingDouble(Open::f).thenComparingDouble(o -> -o.g()));
		Info s = new Info();
		s.g = 0;
		info.put(start.asLong(), s);
		open.add(new Open(start, 0, heuristic(start, goal)));
		BlockPos best = start;
		double bestH = heuristic(start, goal);
		int visited = 0;
		Out out = new Out();
		boolean reached = false;
		int radius = (int) Math.min(160, Math.max(24, Math.sqrt(start.distSqr(goal)) + 24));

		while (!open.isEmpty() && visited < maxNodes) {
			Open cur = open.poll();
			Info ci = info.get(cur.pos().asLong());
			if (cur.g() > ci.g) {
				continue;
			}
			visited++;
			if (cur.pos().equals(goal)) {
				reached = true;
				best = cur.pos();
				break;
			}
			double h = heuristic(cur.pos(), goal);
			if (h < bestH) {
				bestH = h;
				best = cur.pos();
			}
			out.clear();
			expand(t, cur.pos(), out);
			for (int i = 0; i < out.pos.size(); i++) {
				BlockPos n = out.pos.get(i);
				if (Math.abs(n.getX() - start.getX()) > radius || Math.abs(n.getZ() - start.getZ()) > radius) {
					continue;
				}
				double g = cur.g() + out.cost.get(i);
				Info ni = info.computeIfAbsent(n.asLong(), k -> new Info());
				if (g < ni.g) {
					ni.g = g;
					ni.parent = cur.pos();
					ni.move = out.move.get(i);
					ni.door = out.door.get(i);
					open.add(new Open(n, g, g + heuristic(n, goal)));
				}
			}
		}

		List<Node> nodes = new ArrayList<>();
		BlockPos p = best;
		while (p != null) {
			Info pi = info.get(p.asLong());
			nodes.add(new Node(p, pi.move, pi.door));
			p = pi.parent;
		}
		Collections.reverse(nodes);
		return new Result(nodes, reached, visited, info.get(best.asLong()).g);
	}

	private static double heuristic(BlockPos a, BlockPos b) {
		double dx = Math.abs(a.getX() - b.getX());
		double dz = Math.abs(a.getZ() - b.getZ());
		double dy = Math.abs(a.getY() - b.getY());
		double diag = Math.min(dx, dz);
		return (Math.max(dx, dz) - diag) + diag * 1.4143 + dy;
	}

	private static void expand(Terrain t, BlockPos p, Out out) {
		boolean inWater = t.isWater(p) || t.isWater(p.above());
		double waterPenalty = inWater ? 2.0 : 0.0;

		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos side = p.relative(d);
			boolean door = t.needsOpening(side);
			double doorCost = door ? 4.0 : 0.0;

			if (t.valid(side)) {
				double c = 1.0 + doorCost + (t.isWater(side) ? 2.0 : 0.0) + (t.hazardAround(side) ? 3.0 : 0.0);
				out.add(side, Move.WALK, c, door);
			} else if (t.bodyFree(side)) {
				// nothing to stand on: step off and drop down
				for (int fall = 1; fall <= MAX_FALL_INTO_WATER; fall++) {
					BlockPos land = side.below(fall);
					if (!t.passable(land) || !t.passable(land.above())) {
						break;
					}
					if (t.isWater(land)) {
						out.add(land, Move.DESCEND, 2.0 + fall * 0.3 + doorCost, door);
						break;
					}
					if (t.valid(land)) {
						if (fall <= MAX_FALL) {
							out.add(land, Move.DESCEND, 1.0 + fall * 0.6 + doorCost + (t.hazardAround(land) ? 3.0 : 0.0), door);
						}
						break;
					}
				}
			}

			// jump up one block: head clearance above the current spot, free body and ground at the higher block
			BlockPos up = side.above();
			if (t.passable(p.above(2)) && t.valid(up) && !t.needsOpening(up) && t.standable(up.below())) {
				out.add(up, Move.ASCEND, 2.2 + waterPenalty + (t.hazardAround(up) ? 3.0 : 0.0), false);
			}
		}

		// diagonals: both orthogonal neighbours must be free so the bot does not clip a corner
		for (Direction a : Direction.Plane.HORIZONTAL) {
			Direction b = a.getClockWise();
			BlockPos n = p.relative(a).relative(b);
			if (t.bodyFree(p.relative(a)) && t.bodyFree(p.relative(b)) && !t.needsOpening(p.relative(a)) && !t.needsOpening(p.relative(b))
				&& t.valid(n) && !t.needsOpening(n)) {
				out.add(n, Move.DIAGONAL, 1.4143 + (t.isWater(n) ? 2.0 : 0.0) + (t.hazardAround(n) ? 3.0 : 0.0), false);
			}
		}

		// vertical: swimming and ladders
		if (inWater && t.passable(p.above()) && t.passable(p.above(2)) && (t.isWater(p.above()) || t.isWater(p.above(2)))) {
			out.add(p.above(), Move.SWIM_UP, 2.0, false);
		}
		if (inWater && t.passable(p.below()) && t.isWater(p.below())) {
			out.add(p.below(), Move.SWIM_DOWN, 1.5, false);
		}
		if (t.climbable(p) || t.climbable(p.above())) {
			BlockPos up = p.above();
			if (t.bodyFree(up) && (t.climbable(up) || t.standable(up.below()))) {
				out.add(up, Move.CLIMB_UP, 2.0, false);
			}
		}
		if (t.climbable(p.below())) {
			BlockPos down = p.below();
			if (t.bodyFree(down)) {
				out.add(down, Move.CLIMB_DOWN, 1.5, false);
			}
		}
	}
}
