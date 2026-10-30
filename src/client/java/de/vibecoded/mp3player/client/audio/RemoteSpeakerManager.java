package de.vibecoded.mp3player.client.audio;

import de.vibecoded.mp3player.client.audio.Opus3dStreamCodec.RemoteOpusDecodeWorker;
import de.vibecoded.mp3player.client.config.Mp3Config;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStop;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/**
 * Manages incoming 48 kHz Opus 3D positional audio streams from other players
 * in Multiplayer / Essential worlds (supporting all 6 source audio formats).
 */
public final class RemoteSpeakerManager {
	public static final class RemoteStream implements AutoCloseable {
		private final UUID speakerUuid;
		private final String speakerName;
		private final UUID streamId;
		private final String trackTitle;
		private final RemoteOpusDecodeWorker decodeWorker;
		private final OpenAlStreamChannel alChannel;
		private volatile float speakerVolume;
		private volatile boolean paused;
		private volatile long lastPacketMs;

		RemoteStream(UUID speakerUuid, String speakerName, UUID streamId, String trackTitle, float volume) {
			this.speakerUuid = speakerUuid;
			this.speakerName = speakerName;
			this.streamId = streamId;
			this.trackTitle = trackTitle;
			this.speakerVolume = volume;
			this.paused = false;
			this.lastPacketMs = System.currentTimeMillis();
			this.decodeWorker = new RemoteOpusDecodeWorker(speakerName);
			this.alChannel = new OpenAlStreamChannel(true);
			this.decodeWorker.start();
		}

		public String speakerName() {
			return speakerName;
		}

		public String trackTitle() {
			return trackTitle;
		}

		public boolean paused() {
			return paused;
		}

		void pushAudioPacket(byte[] bytes) {
			this.lastPacketMs = System.currentTimeMillis();
			this.decodeWorker.pushPacket(bytes);
		}

		void updateState(boolean paused, float volume) {
			this.paused = paused;
			this.speakerVolume = volume;
			this.lastPacketMs = System.currentTimeMillis();
		}

		@Override
		public void close() {
			decodeWorker.close();
			alChannel.close();
		}
	}

	private final Mp3Config config;
	private final Map<UUID, RemoteStream> activeStreams = new ConcurrentHashMap<>();

	public RemoteSpeakerManager(Mp3Config config) {
		this.config = config;
	}

	public Collection<RemoteStream> activeStreams() {
		return activeStreams.values();
	}

	public int activeCount() {
		return activeStreams.size();
	}

	public void onStreamStart(S2CStreamStart payload) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.getUUID().equals(payload.speakerUuid())) {
			return;
		}
		RemoteStream old = activeStreams.remove(payload.speakerUuid());
		if (old != null) {
			old.close();
		}
		RemoteStream stream = new RemoteStream(
				payload.speakerUuid(),
				payload.speakerName(),
				payload.streamId(),
				payload.trackTitle(),
				payload.volume()
		);
		activeStreams.put(payload.speakerUuid(), stream);
	}

	public void onStreamChunk(S2CStreamChunk payload) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.getUUID().equals(payload.speakerUuid())) {
			return;
		}
		RemoteStream stream = activeStreams.get(payload.speakerUuid());
		if (stream == null || !stream.streamId.equals(payload.streamId())) {
			if (stream != null) {
				stream.close();
			}
			stream = new RemoteStream(payload.speakerUuid(), "Spieler", payload.streamId(), "3D-Stream", 0.8f);
			activeStreams.put(payload.speakerUuid(), stream);
		}
		stream.pushAudioPacket(payload.mp3Frames());
	}

	public void onStreamState(S2CStreamState payload) {
		RemoteStream stream = activeStreams.get(payload.speakerUuid());
		if (stream != null && stream.streamId.equals(payload.streamId())) {
			stream.updateState(payload.paused(), payload.volume());
		}
	}

	public void onStreamStop(S2CStreamStop payload) {
		RemoteStream stream = activeStreams.get(payload.speakerUuid());
		if (stream != null && stream.streamId.equals(payload.streamId())) {
			activeStreams.remove(payload.speakerUuid(), stream);
			stream.close();
		}
	}

	public void tick(Minecraft client) {
		if (activeStreams.isEmpty()) {
			return;
		}
		if (!Mp3AudioController.hasActiveWorldConnection(client)) {
			stopAll();
			return;
		}

		float masterVol = client.options.getSoundSourceVolume(SoundSource.MASTER);
		long now = System.currentTimeMillis();

		Iterator<Map.Entry<UUID, RemoteStream>> it = activeStreams.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, RemoteStream> entry = it.next();
			RemoteStream stream = entry.getValue();

			long timeout = stream.paused ? 300_000L : 15_000L;
			if (now - stream.lastPacketMs > timeout) {
				it.remove();
				stream.close();
				continue;
			}

			Player speakerEntity = findPlayer(client, stream.speakerUuid);
			if (speakerEntity == null) {
				stream.alChannel.pump(stream.decodeWorker.outputQueue(), stream.paused, 0.0f, 3);
				continue;
			}

			stream.alChannel.setWorldPosition(
					speakerEntity.getX(),
					speakerEntity.getY() + 1.15,
					speakerEntity.getZ()
			);

			float effectiveGain = config.muteOthers() ? 0.0f : (stream.speakerVolume * masterVol);
			boolean finished = stream.alChannel.pump(
					stream.decodeWorker.outputQueue(),
					stream.paused,
					effectiveGain,
					3
			);
			if (finished) {
				it.remove();
				stream.close();
			}
		}
	}

	private static Player findPlayer(Minecraft client, UUID uuid) {
		if (client.level == null) {
			return null;
		}
		for (Player p : client.level.players()) {
			if (p.getUUID().equals(uuid)) {
				return p;
			}
		}
		return null;
	}

	public void stopAll() {
		for (RemoteStream stream : activeStreams.values()) {
			stream.close();
		}
		activeStreams.clear();
	}
}
