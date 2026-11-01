package de.vibecoded.mp3player.network;

import de.vibecoded.mp3player.Mp3PlayerMod;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Compact, Essential-compatible custom payloads.
 * Every chunk packet is strictly capped at 4 KB (well under Vanilla's 32 KB C2S
 * and Essential's 1 MB S2C packet size limits) and requires no extra UDP ports.
 */
public final class Mp3Payloads {
	public static final int MAX_CHUNK_BYTES = 4096;
	public static final int MAX_LINKED_SPEAKERS = 64;
	private static final int MAX_TEXT_LEN = 256;

	private Mp3Payloads() {
	}

	/**
	 * Represents a linked 3D Speaker block position and its individual volume multiplier (0.0 .. 1.0).
	 */
	public record SpeakerTarget(BlockPos pos, float volume) {
		public SpeakerTarget(BlockPos pos, float volume) {
			this.pos = pos != null ? pos.immutable() : BlockPos.ZERO;
			this.volume = clampVolume(volume);
		}
	}

	// ===================== CLIENT -> SERVER =====================

	public record C2SStreamStart(
			UUID streamId,
			String trackTitle,
			float volume,
			Optional<BlockPos> boomboxPos,
			boolean emitFromPlayer,
			List<SpeakerTarget> speakers
	) implements CustomPacketPayload {
		public static final Type<C2SStreamStart> TYPE = new Type<>(Mp3PlayerMod.id("c2s_stream_start"));
		public static final StreamCodec<RegistryFriendlyByteBuf, C2SStreamStart> CODEC = StreamCodec.of(
				(buf, val) -> {
					buf.writeUUID(val.streamId());
					buf.writeUtf(truncate(val.trackTitle()), MAX_TEXT_LEN);
					buf.writeFloat(clampVolume(val.volume()));
					writeOptionalBlockPos(buf, val.boomboxPos());
					buf.writeBoolean(val.emitFromPlayer());
					writeSpeakerTargets(buf, val.speakers());
				},
				buf -> new C2SStreamStart(
						buf.readUUID(),
						buf.readUtf(MAX_TEXT_LEN),
						clampVolume(buf.readFloat()),
						readOptionalBlockPos(buf),
						buf.readBoolean(),
						readSpeakerTargets(buf)
				)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record C2SStreamChunk(UUID streamId, int sequence, byte[] mp3Frames) implements CustomPacketPayload {
		public static final Type<C2SStreamChunk> TYPE = new Type<>(Mp3PlayerMod.id("c2s_stream_chunk"));
		public static final StreamCodec<RegistryFriendlyByteBuf, C2SStreamChunk> CODEC = StreamCodec.of(
				(buf, val) -> {
					buf.writeUUID(val.streamId());
					buf.writeVarInt(val.sequence());
					buf.writeByteArray(val.mp3Frames());
				},
				buf -> new C2SStreamChunk(
						buf.readUUID(),
						buf.readVarInt(),
						buf.readByteArray(MAX_CHUNK_BYTES)
				)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record C2SStreamState(
			UUID streamId,
			boolean paused,
			float volume,
			Optional<BlockPos> boomboxPos,
			boolean emitFromPlayer,
			List<SpeakerTarget> speakers
	) implements CustomPacketPayload {
		public static final Type<C2SStreamState> TYPE = new Type<>(Mp3PlayerMod.id("c2s_stream_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, C2SStreamState> CODEC = StreamCodec.of(
				(buf, val) -> {
					buf.writeUUID(val.streamId());
					buf.writeBoolean(val.paused());
					buf.writeFloat(clampVolume(val.volume()));
					writeOptionalBlockPos(buf, val.boomboxPos());
					buf.writeBoolean(val.emitFromPlayer());
					writeSpeakerTargets(buf, val.speakers());
				},
				buf -> new C2SStreamState(
						buf.readUUID(),
						buf.readBoolean(),
						clampVolume(buf.readFloat()),
						readOptionalBlockPos(buf),
						buf.readBoolean(),
						readSpeakerTargets(buf)
				)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record C2SStreamStop(UUID streamId) implements CustomPacketPayload {
		public static final Type<C2SStreamStop> TYPE = new Type<>(Mp3PlayerMod.id("c2s_stream_stop"));
		public static final StreamCodec<RegistryFriendlyByteBuf, C2SStreamStop> CODEC = StreamCodec.of(
				(buf, val) -> buf.writeUUID(val.streamId()),
				buf -> new C2SStreamStop(buf.readUUID())
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// ===================== SERVER -> CLIENT =====================

	public record S2CStreamStart(
			UUID speakerUuid,
			String speakerName,
			UUID streamId,
			String trackTitle,
			float volume,
			Optional<BlockPos> boomboxPos,
			boolean emitFromPlayer,
			List<SpeakerTarget> speakers
	) implements CustomPacketPayload {
		public static final Type<S2CStreamStart> TYPE = new Type<>(Mp3PlayerMod.id("s2c_stream_start"));
		public static final StreamCodec<RegistryFriendlyByteBuf, S2CStreamStart> CODEC = StreamCodec.of(
				(buf, val) -> {
					buf.writeUUID(val.speakerUuid());
					buf.writeUtf(truncate(val.speakerName()), 64);
					buf.writeUUID(val.streamId());
					buf.writeUtf(truncate(val.trackTitle()), MAX_TEXT_LEN);
					buf.writeFloat(clampVolume(val.volume()));
					writeOptionalBlockPos(buf, val.boomboxPos());
					buf.writeBoolean(val.emitFromPlayer());
					writeSpeakerTargets(buf, val.speakers());
				},
				buf -> new S2CStreamStart(
						buf.readUUID(),
						buf.readUtf(64),
						buf.readUUID(),
						buf.readUtf(MAX_TEXT_LEN),
						clampVolume(buf.readFloat()),
						readOptionalBlockPos(buf),
						buf.readBoolean(),
						readSpeakerTargets(buf)
				)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CStreamChunk(
			UUID speakerUuid,
			UUID streamId,
			int sequence,
			byte[] mp3Frames,
			Optional<BlockPos> boomboxPos
	) implements CustomPacketPayload {
		public static final Type<S2CStreamChunk> TYPE = new Type<>(Mp3PlayerMod.id("s2c_stream_chunk"));
		public static final StreamCodec<RegistryFriendlyByteBuf, S2CStreamChunk> CODEC = StreamCodec.of(
				(buf, val) -> {
					buf.writeUUID(val.speakerUuid());
					buf.writeUUID(val.streamId());
					buf.writeVarInt(val.sequence());
					buf.writeByteArray(val.mp3Frames());
					writeOptionalBlockPos(buf, val.boomboxPos());
				},
				buf -> new S2CStreamChunk(
						buf.readUUID(),
						buf.readUUID(),
						buf.readVarInt(),
						buf.readByteArray(MAX_CHUNK_BYTES),
						readOptionalBlockPos(buf)
				)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CStreamState(
			UUID speakerUuid,
			UUID streamId,
			boolean paused,
			float volume,
			Optional<BlockPos> boomboxPos,
			boolean emitFromPlayer,
			List<SpeakerTarget> speakers
	) implements CustomPacketPayload {
		public static final Type<S2CStreamState> TYPE = new Type<>(Mp3PlayerMod.id("s2c_stream_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, S2CStreamState> CODEC = StreamCodec.of(
				(buf, val) -> {
					buf.writeUUID(val.speakerUuid());
					buf.writeUUID(val.streamId());
					buf.writeBoolean(val.paused());
					buf.writeFloat(clampVolume(val.volume()));
					writeOptionalBlockPos(buf, val.boomboxPos());
					buf.writeBoolean(val.emitFromPlayer());
					writeSpeakerTargets(buf, val.speakers());
				},
				buf -> new S2CStreamState(
						buf.readUUID(),
						buf.readUUID(),
						buf.readBoolean(),
						clampVolume(buf.readFloat()),
						readOptionalBlockPos(buf),
						buf.readBoolean(),
						readSpeakerTargets(buf)
				)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CStreamStop(UUID speakerUuid, UUID streamId) implements CustomPacketPayload {
		public static final Type<S2CStreamStop> TYPE = new Type<>(Mp3PlayerMod.id("s2c_stream_stop"));
		public static final StreamCodec<RegistryFriendlyByteBuf, S2CStreamStop> CODEC = StreamCodec.of(
				(buf, val) -> {
					buf.writeUUID(val.speakerUuid());
					buf.writeUUID(val.streamId());
				},
				buf -> new S2CStreamStop(buf.readUUID(), buf.readUUID())
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.serverboundPlay().register(C2SStreamStart.TYPE, C2SStreamStart.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(C2SStreamChunk.TYPE, C2SStreamChunk.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(C2SStreamState.TYPE, C2SStreamState.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(C2SStreamStop.TYPE, C2SStreamStop.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(S2CStreamStart.TYPE, S2CStreamStart.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CStreamChunk.TYPE, S2CStreamChunk.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CStreamState.TYPE, S2CStreamState.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CStreamStop.TYPE, S2CStreamStop.CODEC);
	}

	private static void writeOptionalBlockPos(RegistryFriendlyByteBuf buf, Optional<BlockPos> optPos) {
		if (optPos != null && optPos.isPresent()) {
			buf.writeBoolean(true);
			buf.writeBlockPos(optPos.get());
		} else {
			buf.writeBoolean(false);
		}
	}

	private static Optional<BlockPos> readOptionalBlockPos(RegistryFriendlyByteBuf buf) {
		return buf.readBoolean() ? Optional.of(buf.readBlockPos()) : Optional.empty();
	}

	private static void writeSpeakerTargets(RegistryFriendlyByteBuf buf, List<SpeakerTarget> speakers) {
		if (speakers == null || speakers.isEmpty()) {
			buf.writeVarInt(0);
			return;
		}
		int count = Math.min(MAX_LINKED_SPEAKERS, speakers.size());
		buf.writeVarInt(count);
		for (int i = 0; i < count; i++) {
			SpeakerTarget target = speakers.get(i);
			buf.writeBlockPos(target.pos());
			buf.writeFloat(clampVolume(target.volume()));
		}
	}

	private static List<SpeakerTarget> readSpeakerTargets(RegistryFriendlyByteBuf buf) {
		int count = Math.max(0, Math.min(MAX_LINKED_SPEAKERS, buf.readVarInt()));
		if (count == 0) {
			return Collections.emptyList();
		}
		List<SpeakerTarget> list = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			BlockPos pos = buf.readBlockPos().immutable();
			float vol = clampVolume(buf.readFloat());
			list.add(new SpeakerTarget(pos, vol));
		}
		return Collections.unmodifiableList(list);
	}

	private static String truncate(String s) {
		if (s == null) {
			return "";
		}
		return s.length() > MAX_TEXT_LEN ? s.substring(0, MAX_TEXT_LEN) : s;
	}

	private static float clampVolume(float v) {
		if (Float.isNaN(v)) {
			return 0.8f;
		}
		return Math.max(0.0f, Math.min(1.0f, v));
	}
}
