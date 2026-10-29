package de.vibecoded.mp3player.client.debug;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.vibecoded.mp3player.Mp3PlayerMod;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/**
 * In-game client helper (/mp3timeout) to reliably reproduce genuine Netty ReadTimeoutException
 * disconnects and pre-timeout packet-loss freezes when testing via Modrinth (Multiplayer, Essential, LAN, Singleplayer).
 */
public final class TimeoutSimulationHelper {
	private static final String HANDLER_NAME = "mp3player_timeout_blackhole";
	private static final int DEFAULT_FREEZE_SECONDS = 8;

	private static Field cachedChannelField;
	private static volatile Connection frozenConnection;
	private static volatile long freezeDeadlineMs = 0L;
	private static int lastReportedSecond = -1;

	private TimeoutSimulationHelper() {
	}

	/**
	 * Drops all decoded inbound and outbound packets right before "packet_handler"
	 * while keeping underlying AES/CFB8 cipher & compression state intact if cancelled.
	 */
	private static final class PacketBlackholeHandler extends ChannelDuplexHandler {
		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			ReferenceCountUtil.release(msg);
		}

		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			ReferenceCountUtil.release(msg);
			promise.trySuccess();
		}
	}

	public static void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommands.literal("mp3timeout")
					.executes(ctx -> triggerInstantTimeout(ctx.getSource()))
					.then(ClientCommands.literal("now")
							.executes(ctx -> triggerInstantTimeout(ctx.getSource())))
					.then(ClientCommands.literal("freeze")
							.executes(ctx -> startFreezeTimeout(ctx.getSource(), DEFAULT_FREEZE_SECONDS))
							.then(ClientCommands.argument("seconds", IntegerArgumentType.integer(1, 60))
									.executes(ctx -> startFreezeTimeout(
											ctx.getSource(),
											IntegerArgumentType.getInteger(ctx, "seconds")
									))))
					.then(ClientCommands.literal("cancel")
							.executes(ctx -> cancelFreeze(ctx.getSource())))
			);
		});
	}

	private static int triggerInstantTimeout(FabricClientCommandSource source) {
		Minecraft client = source.getClient();
		Connection connection = getActiveConnection(client);
		if (connection == null) {
			source.sendError(Component.translatable("command.mp3player.timeout.no_connection"));
			return 0;
		}
		reset();
		fireReadTimeout(connection);
		return 1;
	}

	private static int startFreezeTimeout(FabricClientCommandSource source, int seconds) {
		Minecraft client = source.getClient();
		Connection connection = getActiveConnection(client);
		if (connection == null) {
			source.sendError(Component.translatable("command.mp3player.timeout.no_connection"));
			return 0;
		}
		Channel channel = extractNettyChannel(connection);
		if (channel == null || !channel.isOpen()) {
			source.sendError(Component.translatable("command.mp3player.timeout.no_connection"));
			return 0;
		}

		channel.eventLoop().execute(() -> {
			ChannelPipeline pipeline = channel.pipeline();
			if (pipeline.get(HANDLER_NAME) != null) {
				pipeline.remove(HANDLER_NAME);
			}
			ChannelHandlerContext targetCtx = pipeline.context(connection);
			if (targetCtx != null) {
				pipeline.addBefore(targetCtx.name(), HANDLER_NAME, new PacketBlackholeHandler());
			} else if (pipeline.get("packet_handler") != null) {
				pipeline.addBefore("packet_handler", HANDLER_NAME, new PacketBlackholeHandler());
			} else {
				pipeline.addFirst(HANDLER_NAME, new PacketBlackholeHandler());
			}
		});

		frozenConnection = connection;
		freezeDeadlineMs = System.currentTimeMillis() + (seconds * 1000L);
		lastReportedSecond = -1;

		source.sendFeedback(Component.translatable("command.mp3player.timeout.freeze_started", seconds)
				.withStyle(ChatFormatting.GOLD));
		return 1;
	}

	private static int cancelFreeze(FabricClientCommandSource source) {
		if (frozenConnection == null) {
			source.sendError(Component.translatable("command.mp3player.timeout.not_active"));
			return 0;
		}
		reset();
		source.sendFeedback(Component.translatable("command.mp3player.timeout.cancelled")
				.withStyle(ChatFormatting.GREEN));
		return 1;
	}

	public static void tick(Minecraft client) {
		Connection conn = frozenConnection;
		if (conn == null) {
			return;
		}
		if (!conn.isConnected() || client.level == null || client.player == null) {
			reset();
			return;
		}

		long remainingMs = freezeDeadlineMs - System.currentTimeMillis();
		if (remainingMs <= 0L) {
			reset();
			fireReadTimeout(conn);
			return;
		}

		int remSec = (int) Math.ceil(remainingMs / 1000.0);
		if (remSec != lastReportedSecond) {
			lastReportedSecond = remSec;
			client.player.sendOverlayMessage(
					Component.translatable("command.mp3player.timeout.countdown", remSec)
							.withStyle(ChatFormatting.GOLD)
			);
		}
	}

	public static void reset() {
		Connection conn = frozenConnection;
		frozenConnection = null;
		freezeDeadlineMs = 0L;
		lastReportedSecond = -1;

		if (conn != null) {
			Channel channel = extractNettyChannel(conn);
			if (channel != null && channel.isOpen()) {
				channel.eventLoop().execute(() -> {
					ChannelPipeline pipeline = channel.pipeline();
					if (pipeline.get(HANDLER_NAME) != null) {
						try {
							pipeline.remove(HANDLER_NAME);
						} catch (Exception ignored) {
						}
					}
				});
			}
		}
	}

	private static void fireReadTimeout(Connection connection) {
		Channel channel = extractNettyChannel(connection);
		if (channel != null && channel.isOpen()) {
			channel.eventLoop().execute(() -> {
				try {
					ChannelPipeline pipeline = channel.pipeline();
					if (pipeline.get(HANDLER_NAME) != null) {
						pipeline.remove(HANDLER_NAME);
					}
					ChannelHandlerContext ctx = pipeline.context(connection);
					connection.exceptionCaught(ctx, ReadTimeoutException.INSTANCE);
				} catch (Exception e) {
					Mp3PlayerMod.LOGGER.warn("Fallback timeout disconnect triggered", e);
					connection.disconnect(Component.translatable("disconnect.timeout"));
				}
			});
		} else {
			try {
				connection.exceptionCaught(null, ReadTimeoutException.INSTANCE);
			} catch (Exception e) {
				connection.disconnect(Component.translatable("disconnect.timeout"));
			}
		}
	}

	private static Connection getActiveConnection(Minecraft client) {
		if (client == null) {
			return null;
		}
		ClientPacketListener listener = client.getConnection();
		if (listener == null) {
			return null;
		}
		Connection conn = listener.getConnection();
		return (conn != null && conn.isConnected()) ? conn : null;
	}

	private static Channel extractNettyChannel(Connection connection) {
		try {
			Field field = cachedChannelField;
			if (field == null) {
				for (Field f : Connection.class.getDeclaredFields()) {
					if (Channel.class.isAssignableFrom(f.getType())) {
						f.setAccessible(true);
						field = f;
						cachedChannelField = f;
						break;
					}
				}
			}
			if (field != null) {
				return (Channel) field.get(connection);
			}
		} catch (Exception e) {
			Mp3PlayerMod.LOGGER.warn("Could not access Netty channel on Connection", e);
		}
		return null;
	}
}

