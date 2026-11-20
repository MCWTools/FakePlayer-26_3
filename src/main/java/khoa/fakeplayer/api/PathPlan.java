package khoa.fakeplayer.api;

import java.util.List;

import net.minecraft.core.BlockPos;

/** Outcome of a path search. {@code partial} = the goal was not reachable, the path leads to the closest explored position. */
public record PathPlan(boolean found, boolean partial, int nodes, double cost, List<BlockPos> waypoints, String message) {
}
