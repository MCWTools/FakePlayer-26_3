package khoa.fakeplayer.bot;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;

/**
 * The "network cable" of a bot: a real {@link Connection} on an in-memory {@link EmbeddedChannel}. Everything the
 * server sends to the bot is dropped, except the few packets a real client would answer (teleport confirmation,
 * chunk batch ack). Those answers are queued and delivered on the server thread at the start of the bot's next tick,
 * exactly like a client reply arriving over the network.
 */
public final class BotConnection extends Connection {
	private final Queue<Packet<? super ServerGamePacketListener>> replies = new ArrayDeque<>();
	private final EmbeddedChannel embedded;
	private Consumer<Packet<?>> sniffer;
	private volatile int selfId = -1;
	private final Queue<net.minecraft.world.phys.Vec3> motions = new java.util.concurrent.ConcurrentLinkedQueue<>();

	public void setSelfId(int id) {
		this.selfId = id;
	}

	/** Velocity the server pushed to this player (knockback, explosions): a real client would apply it itself. */
	public net.minecraft.world.phys.Vec3 pollMotion() {
		return this.motions.poll();
	}

	public BotConnection() {
		super(PacketFlow.SERVERBOUND);
		this.embedded = new EmbeddedChannel(this);
	}

	/** Optional observer of every clientbound packet (debug and API use). */
	public void setSniffer(Consumer<Packet<?>> sniffer) {
		this.sniffer = sniffer;
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
		if (this.sniffer != null) {
			this.sniffer.accept(packet);
		}
		if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket m && m.id() == this.selfId) {
			this.motions.add(m.movement());
		} else if (packet instanceof ClientboundPlayerPositionPacket pos) {
			var c = pos.change();
			this.replies.add(new ServerboundAcceptTeleportationPacket(pos.id(), c.position().x, c.position().y, c.position().z, c.yRot(), c.xRot()));
		} else if (packet instanceof ClientboundChunkBatchFinishedPacket) {
			this.replies.add(new ServerboundChunkBatchReceivedPacket(25.0F));
		}
		if (listener != null) {
			try {
				listener.operationComplete(this.embedded.newSucceededFuture());
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
	}

	public Packet<? super ServerGamePacketListener> pollReply() {
		return this.replies.poll();
	}

	public static boolean isChat(Packet<?> p) {
		return p instanceof ClientboundSystemChatPacket;
	}

	public static boolean isDisconnect(Packet<?> p) {
		return p instanceof ClientboundDisconnectPacket;
	}
}
