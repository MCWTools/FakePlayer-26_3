package khoa.fakeplayer.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Block classification for path-finding. Unloaded chunks count as solid so the bot never plans through them. */
final class Terrain {
	private final Level level;

	Terrain(Level level) {
		this.level = level;
	}

	boolean loaded(BlockPos pos) {
		return this.level.hasChunkAt(pos) && pos.getY() >= this.level.getMinY() && pos.getY() <= this.level.getMaxY();
	}

	BlockState state(BlockPos pos) {
		return this.level.getBlockState(pos);
	}

	/** Closed wooden door / fence gate: the bot can open it by hand. */
	boolean isClosedOpenable(BlockState s) {
		Block b = s.getBlock();
		if (b instanceof DoorBlock) {
			return !s.getValue(DoorBlock.OPEN) && s.is(BlockTags.WOODEN_DOORS);
		}
		if (b instanceof FenceGateBlock) {
			return !s.getValue(FenceGateBlock.OPEN);
		}
		return false;
	}

	boolean isWater(BlockPos pos) {
		return this.state(pos).getFluidState().is(FluidTags.WATER);
	}

	/** Harmful or unusable to stand in / walk through, regardless of collision. */
	boolean isHazard(BlockState s) {
		if (s.getFluidState().is(FluidTags.LAVA)) {
			return true;
		}
		return s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.COBWEB)
			|| s.is(Blocks.POWDER_SNOW) || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
			|| s.is(Blocks.CACTUS) || s.is(Blocks.END_PORTAL) || s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.BUBBLE_COLUMN);
	}

	/** Can the player body occupy this block (open air, water, plants, open doors, ...). */
	boolean passable(BlockPos pos) {
		if (!this.loaded(pos)) {
			return false;
		}
		BlockState s = this.state(pos);
		if (this.isHazard(s)) {
			return false;
		}
		if (this.isClosedOpenable(s)) {
			return true;
		}
		return s.getCollisionShape(this.level, pos).isEmpty();
	}

	/** Solid ground whose top is at most one block high (fences and walls are too tall to stand on). */
	boolean standable(BlockPos floor) {
		if (!this.loaded(floor)) {
			return false;
		}
		BlockState s = this.state(floor);
		if (s.is(Blocks.MAGMA_BLOCK) || this.isHazard(s)) {
			return false;
		}
		VoxelShape shape = s.getCollisionShape(this.level, floor);
		if (shape.isEmpty()) {
			return false;
		}
		double top = shape.max(Direction.Axis.Y);
		return top >= 0.5 && top <= 1.0 + 1.0E-6;
	}

	boolean climbable(BlockPos pos) {
		return this.loaded(pos) && this.state(pos).is(BlockTags.CLIMBABLE);
	}

	/** Two blocks of free space for feet and head. */
	boolean bodyFree(BlockPos feet) {
		return this.passable(feet) && this.passable(feet.above());
	}

	/** A node is a place the bot can be: free body plus ground, water or a ladder. */
	boolean valid(BlockPos feet) {
		if (!this.bodyFree(feet)) {
			return false;
		}
		return this.standable(feet.below()) || this.isWater(feet) || this.climbable(feet);
	}

	boolean hazardAround(BlockPos feet) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockState s = this.state(feet.relative(d));
			if (s.getFluidState().is(FluidTags.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.CACTUS)) {
				return true;
			}
		}
		return false;
	}

	/** True when entering this node means opening a closed door or gate. */
	boolean needsOpening(BlockPos feet) {
		return this.isClosedOpenable(this.state(feet)) || this.isClosedOpenable(this.state(feet.above()));
	}
}
