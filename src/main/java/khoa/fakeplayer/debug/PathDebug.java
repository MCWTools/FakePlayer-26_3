package khoa.fakeplayer.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;

import khoa.fakeplayer.bot.Bot;
import khoa.fakeplayer.bot.BotManager;

/** Draws the active path of every bot with debug enabled as particles (visible to nearby players). */
public final class PathDebug {
	private static int counter;

	private PathDebug() {
	}

	public static void tick() {
		if (++counter % 5 != 0) {
			return;
		}
		for (Bot b : BotManager.all()) {
			if (!b.debug || b.player() == null) {
				continue;
			}
			ServerLevel level = b.player().level();
			for (BlockPos p : b.currentPath()) {
				level.sendParticles(ParticleTypes.END_ROD, p.getX() + 0.5, p.getY() + 0.1, p.getZ() + 0.5, 1, 0, 0, 0, 0);
			}
		}
	}
}
