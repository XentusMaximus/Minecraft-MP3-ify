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
	public static final int MAX_LINKED_SPEAKERS = Integer.MAX_VALUE;
	public static final int MAX_QUEUE_ENTRIES = 64;
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

	/**
	 * Represents a single entry in the shared Speaker Playback Queue, including who added it.
	 */
	public record QueueEntryInfo(
			UUID entryId,
			UUID ownerUuid,
			String ownerName,
			String trackId,
			String trackTitle,
			int durationSeconds,
			boolean nowPlaying,
			boolean paused
	) {
		public QueueEntryInfo(
				UUID entryId,
				UUID ownerUuid,
				String ownerName,
				String trackId,
				String trackTitle,
				int durationSeconds,
				boolean nowPlaying,
				boolean paused
		) {
			this.entryId = entryId != null ? entryId : UUID.randomUUID();
			this.ownerUuid = ownerUuid != null ? ownerUuid : new UUID(0L, 0L);
			this.ownerName = truncate(ownerName, 64);
			this.trackId = truncate(trackId, MAX_TEXT_LEN);
			this.trackTitle = truncate(trackTitle, MAX_TEXT_LEN);
			this.durationSeconds = Math.max(0, durationSeconds);
			this.nowPlaying = nowPlaying;
			this.paused = paused;
		}
	}

	public enum QueueActionType {
		REMOVE_ENTRY,
		SKIP_CURRENT,
		CLEAR_ALL,
		ADVANCE_FINISHED,
		SYNC_SPEAKERS
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

	// ===================== SPEAKER QUEUE PAYLOADS =====================

	public record C2SQueueAdd(
			String trackId,
			String trackTitle,
			int durationSeconds,
			boolean switchActiveIfOwner,
			List<SpeakerTarget> speakers
	) implements CustomPacketPayload {
		public static final Type<C2SQueueAdd> TYPE = new Type<>(Mp3PlayerMod.id("c2s_queue_add"));
		public static final StreamCodec<RegistryFriendlyByteBuf, C2SQueueAdd> CODEC = StreamCodec.of(
				(buf, val) -> {
					buf.writeUtf(truncate(val.trackId()), MAX_TEXT_LEN);
					buf.writeUtf(truncate(val.trackTitle()), MAX_TEXT_LEN);
					buf.writeVarInt(Math.max(0, val.durationSeconds()));
					buf.writeBoolean(val.switchActiveIfOwner());
					writeSpeakerTargets(buf, val.speakers());
				},
				buf -> new C2SQueueAdd(
						buf.readUtf(MAX_TEXT_LEN),
						buf.readUtf(MAX_TEXT_LEN),
						Math.max(0, buf.readVarInt()),
						buf.readBoolean(),
						readSpeakerTargets(buf)
				)
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record C2SQueueAction(
			QueueActionType action,
			Optional<UUID> targetEntryId,
			List<SpeakerTarget> speakers
	) implements CustomPacketPayload {
		public static final Type<C2SQueueAction> TYPE = new Type<>(Mp3PlayerMod.id("c2s_queue_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, C2SQueueAction> CODEC = StreamCodec.of(
				(buf, val) -> {
					buf.writeVarInt(val.action() != null ? val.action().ordinal() : 0);
					if (val.targetEntryId() != null && val.targetEntryId().isPresent()) {
						buf.writeBoolean(true);
						buf.writeUUID(val.targetEntryId().get());
					} else {
						buf.writeBoolean(false);
					}
					writeSpeakerTargets(buf, val.speakers());
				},
				buf -> {
					int ord = buf.readVarInt();
					QueueActionType[] values = QueueActionType.values();
					QueueActionType act = (ord >= 0 && ord < values.length) ? values[ord] : QueueActionType.SYNC_SPEAKERS;
					Optional<UUID> entryId = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
					List<SpeakerTarget> spk = readSpeakerTargets(buf);
					return new C2SQueueAction(act, entryId, spk);
				}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CSpeakerQueueSync(
			List<QueueEntryInfo> entries,
			int skipVotes,
			int requiredSkipVotes,
			List<UUID> skipVoterUuids,
			Optional<List<BlockPos>> retainedSpeakers
	) implements CustomPacketPayload {
		public S2CSpeakerQueueSync(
				List<QueueEntryInfo> entries,
				int skipVotes,
				int requiredSkipVotes,
				List<UUID> skipVoterUuids
		) {
			this(entries, skipVotes, requiredSkipVotes, skipVoterUuids, Optional.empty());
		}

		public static final Type<S2CSpeakerQueueSync> TYPE = new Type<>(Mp3PlayerMod.id("s2c_speaker_queue_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, S2CSpeakerQueueSync> CODEC = StreamCodec.of(
				(buf, val) -> {
					List<QueueEntryInfo> list = val.entries();
					if (list == null || list.isEmpty()) {
						buf.writeVarInt(0);
					} else {
						int count = Math.min(MAX_QUEUE_ENTRIES, list.size());
						buf.writeVarInt(count);
						for (int i = 0; i < count; i++) {
							QueueEntryInfo item = list.get(i);
							buf.writeUUID(item.entryId());
							buf.writeUUID(item.ownerUuid());
							buf.writeUtf(truncate(item.ownerName(), 64), 64);
							buf.writeUtf(truncate(item.trackId(), MAX_TEXT_LEN), MAX_TEXT_LEN);
							buf.writeUtf(truncate(item.trackTitle(), MAX_TEXT_LEN), MAX_TEXT_LEN);
							buf.writeVarInt(Math.max(0, item.durationSeconds()));
							buf.writeBoolean(item.nowPlaying());
							buf.writeBoolean(item.paused());
						}
					}
					buf.writeVarInt(Math.max(0, val.skipVotes()));
					buf.writeVarInt(Math.max(0, val.requiredSkipVotes()));
					List<UUID> voters = val.skipVoterUuids();
					if (voters == null || voters.isEmpty()) {
						buf.writeVarInt(0);
					} else {
						int vCount = Math.min(MAX_QUEUE_ENTRIES, voters.size());
						buf.writeVarInt(vCount);
						for (int i = 0; i < vCount; i++) {
							buf.writeUUID(voters.get(i));
						}
					}
					if (val.retainedSpeakers() != null && val.retainedSpeakers().isPresent()) {
						buf.writeBoolean(true);
						List<BlockPos> posList = val.retainedSpeakers().get();
						int pCount = Math.min(MAX_LINKED_SPEAKERS, posList.size());
						buf.writeVarInt(pCount);
						for (int i = 0; i < pCount; i++) {
							buf.writeBlockPos(posList.get(i));
						}
					} else {
						buf.writeBoolean(false);
					}
				},
				buf -> {
					int count = Math.max(0, Math.min(MAX_QUEUE_ENTRIES, buf.readVarInt()));
					List<QueueEntryInfo> list;
					if (count == 0) {
						list = Collections.emptyList();
					} else {
						List<QueueEntryInfo> tmp = new ArrayList<>(count);
						for (int i = 0; i < count; i++) {
							tmp.add(new QueueEntryInfo(
									buf.readUUID(),
									buf.readUUID(),
									buf.readUtf(64),
									buf.readUtf(MAX_TEXT_LEN),
									buf.readUtf(MAX_TEXT_LEN),
									Math.max(0, buf.readVarInt()),
									buf.readBoolean(),
									buf.readBoolean()
							));
						}
						list = Collections.unmodifiableList(tmp);
					}
					int skipVotes = Math.max(0, buf.readVarInt());
					int requiredSkipVotes = Math.max(0, buf.readVarInt());
					int vCount = Math.max(0, Math.min(MAX_QUEUE_ENTRIES, buf.readVarInt()));
					List<UUID> voters;
					if (vCount == 0) {
						voters = Collections.emptyList();
					} else {
						List<UUID> vTmp = new ArrayList<>(vCount);
						for (int i = 0; i < vCount; i++) {
							vTmp.add(buf.readUUID());
						}
						voters = Collections.unmodifiableList(vTmp);
					}
					Optional<List<BlockPos>> retained;
					if (buf.readBoolean()) {
						int pCount = Math.max(0, Math.min(MAX_LINKED_SPEAKERS, buf.readVarInt()));
						List<BlockPos> pTmp = new ArrayList<>(Math.min(pCount, 4096));
						for (int i = 0; i < pCount; i++) {
							pTmp.add(buf.readBlockPos().immutable());
						}
						retained = Optional.of(Collections.unmodifiableList(pTmp));
					} else {
						retained = Optional.empty();
					}
					return new S2CSpeakerQueueSync(list, skipVotes, requiredSkipVotes, voters, retained);
				}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record S2CQueuePlayTrigger(
			UUID entryId,
			String trackId,
			String trackTitle,
			List<SpeakerTarget> speakers
	) implements CustomPacketPayload {
		public static final Type<S2CQueuePlayTrigger> TYPE = new Type<>(Mp3PlayerMod.id("s2c_queue_play_trigger"));
		public static final StreamCodec<RegistryFriendlyByteBuf, S2CQueuePlayTrigger> CODEC = StreamCodec.of(
				(buf, val) -> {
					buf.writeUUID(val.entryId());
					buf.writeUtf(truncate(val.trackId()), MAX_TEXT_LEN);
					buf.writeUtf(truncate(val.trackTitle()), MAX_TEXT_LEN);
					writeSpeakerTargets(buf, val.speakers());
				},
				buf -> new S2CQueuePlayTrigger(
						buf.readUUID(),
						buf.readUtf(MAX_TEXT_LEN),
						buf.readUtf(MAX_TEXT_LEN),
						readSpeakerTargets(buf)
				)
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
		PayloadTypeRegistry.serverboundPlay().register(C2SQueueAdd.TYPE, C2SQueueAdd.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(C2SQueueAction.TYPE, C2SQueueAction.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(S2CStreamStart.TYPE, S2CStreamStart.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CStreamChunk.TYPE, S2CStreamChunk.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CStreamState.TYPE, S2CStreamState.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CStreamStop.TYPE, S2CStreamStop.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CSpeakerQueueSync.TYPE, S2CSpeakerQueueSync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(S2CQueuePlayTrigger.TYPE, S2CQueuePlayTrigger.CODEC);
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
		List<SpeakerTarget> list = new ArrayList<>(Math.min(count, 4096));
		for (int i = 0; i < count; i++) {
			BlockPos pos = buf.readBlockPos().immutable();
			float vol = clampVolume(buf.readFloat());
			list.add(new SpeakerTarget(pos, vol));
		}
		return Collections.unmodifiableList(list);
	}

	private static String truncate(String s) {
		return truncate(s, MAX_TEXT_LEN);
	}

	private static String truncate(String s, int maxLen) {
		if (s == null) {
			return "";
		}
		return s.length() > maxLen ? s.substring(0, maxLen) : s;
	}

	private static float clampVolume(float v) {
		if (Float.isNaN(v)) {
			return 0.8f;
		}
		return Math.max(0.0f, Math.min(1.0f, v));
	}
}
