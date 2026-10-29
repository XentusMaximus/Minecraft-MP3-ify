package de.vibecoded.mp3player.client.audio;

import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.client.config.Mp3Config;
import de.vibecoded.mp3player.client.config.Mp3Config.LoopMode;
import de.vibecoded.mp3player.client.library.Mp3LibraryManager;
import de.vibecoded.mp3player.mp3.Mp3MetadataParser;
import de.vibecoded.mp3player.mp3.Mp3MetadataParser.FrameChunk;
import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import de.vibecoded.mp3player.network.Mp3Payloads;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamStop;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.sounds.SoundSource;

/**
 * Controls local MP3 playback, playlist navigation (Shuffle / Loop / Seek),
 * and synchronized 4 KB frame-aligned 3D network streaming (Essential & Multiplayer safe).
 */
public final class Mp3AudioController {
	private static final long STREAM_PREBUFFER_NANOS = 750_000_000L; // 0.75s smooth network lead
	private static final int MAX_CHUNKS_PER_TICK = 3;                // max 12 KB/tick burst cap

	private final Mp3Config config;
	private final Mp3LibraryManager library;
	private final Random random = new Random();
	private final Deque<Mp3TrackInfo> history = new ArrayDeque<>();

	private Mp3TrackInfo currentTrack;
	private Mp3DecodeWorker decodeWorker;
	private OpenAlStreamChannel alChannel;
	private boolean paused = false;
	private double seekBaseSeconds = 0.0;

	// Outgoing 3D network stream state
	private UUID activeStreamId;
	private RandomAccessFile streamRaf;
	private long streamFileOffset;
	private long streamEndOffset;
	private int streamSequence;
	private long streamStartWallNanos;
	private long streamPausedAtNanos;
	private long streamSentAudioNanos;
	private float lastSentNetworkVolume = -1.0f;
	private int volumeSyncCooldown = 0;

	public Mp3AudioController(Mp3Config config, Mp3LibraryManager library) {
		this.config = config;
		this.library = library;
	}

	public Mp3Config config() {
		return config;
	}

	public Mp3LibraryManager library() {
		return library;
	}

	public Mp3TrackInfo currentTrack() {
		return currentTrack;
	}

	public boolean isPlaying() {
		return currentTrack != null && alChannel != null && !paused;
	}

	public boolean isPaused() {
		return currentTrack != null && alChannel != null && paused;
	}

	public boolean hasActiveTrack() {
		return currentTrack != null && alChannel != null;
	}

	public boolean isServerSupporting3D() {
		Minecraft mc = Minecraft.getInstance();
		return mc.getConnection() != null && ClientPlayNetworking.canSend(C2SStreamStart.TYPE);
	}

	public boolean isCurrentlyStreaming3D() {
		return activeStreamId != null;
	}

	public float visualizerLevel() {
		return alChannel != null ? alChannel.visualizerLevel() : 0.0f;
	}

	public int currentPositionSeconds() {
		if (currentTrack == null || alChannel == null) {
			return 0;
		}
		int pos = (int) Math.round(seekBaseSeconds + alChannel.elapsedSeconds());
		if (currentTrack.durationSeconds() > 0) {
			return Math.min(pos, currentTrack.durationSeconds());
		}
		return Math.max(0, pos);
	}

	public double progressRatio() {
		if (currentTrack == null || alChannel == null || currentTrack.durationSeconds() <= 0) {
			return 0.0;
		}
		double elapsed = seekBaseSeconds + alChannel.elapsedSeconds();
		return Math.max(0.0, Math.min(1.0, elapsed / currentTrack.durationSeconds()));
	}

	public void playTrack(Mp3TrackInfo track) {
		playTrackInternal(track, 0.0, true);
	}

	private void playTrackInternal(Mp3TrackInfo track, double ratio, boolean recordHistory) {
		if (track == null) {
			return;
		}
		if (recordHistory && currentTrack != null && !currentTrack.equals(track)) {
			history.push(currentTrack);
			while (history.size() > 50) {
				history.removeLast();
			}
		}

		stopLocalAndNetwork(true);

		try {
			long startByteOffset = Mp3MetadataParser.findSeekFrameOffset(track, ratio);
			long endByteOffset = track.audioStartOffset() + track.audioByteLength();
			InputStream sliceStream = openBoundedSlice(track, startByteOffset, endByteOffset);

			this.currentTrack = track;
			this.paused = false;
			this.seekBaseSeconds = track.durationSeconds() * Math.max(0.0, Math.min(1.0, ratio));

			this.decodeWorker = new Mp3DecodeWorker("Local", sliceStream, false);
			this.alChannel = new OpenAlStreamChannel(false);
			this.decodeWorker.start();

			if (config.speaker3dMode() && isServerSupporting3D()) {
				startOutgoing3dStream(track, startByteOffset, endByteOffset);
			}
		} catch (IOException e) {
			Mp3PlayerMod.LOGGER.error("Failed to play MP3 track {}", track.file(), e);
			stop();
		}
	}

	public void togglePlayPause() {
		if (currentTrack != null && alChannel != null) {
			setPaused(!paused);
			return;
		}
		List<Mp3TrackInfo> tracks = library.tracks();
		if (!tracks.isEmpty()) {
			if (config.shuffle()) {
				playTrack(tracks.get(random.nextInt(tracks.size())));
			} else {
				playTrack(tracks.getFirst());
			}
		}
	}

	public void setPaused(boolean shouldPause) {
		if (currentTrack == null || alChannel == null || this.paused == shouldPause) {
			return;
		}
		this.paused = shouldPause;
		long now = System.nanoTime();
		if (activeStreamId != null) {
			if (shouldPause) {
				streamPausedAtNanos = now;
			} else if (streamPausedAtNanos != 0L) {
				streamStartWallNanos += (now - streamPausedAtNanos);
				streamPausedAtNanos = 0L;
			}
			sendNetworkStateNow();
		}
	}

	public void stop() {
		stopLocalAndNetwork(true);
		currentTrack = null;
		paused = false;
		seekBaseSeconds = 0.0;
	}

	public void seek(double ratio) {
		if (currentTrack == null) {
			return;
		}
		boolean wasPaused = this.paused;
		playTrackInternal(currentTrack, Math.max(0.0, Math.min(0.995, ratio)), false);
		if (wasPaused) {
			setPaused(true);
		}
	}

	public void nextTrack() {
		List<Mp3TrackInfo> tracks = library.tracks();
		if (tracks.isEmpty()) {
			stop();
			return;
		}
		if (config.shuffle() && tracks.size() > 1) {
			Mp3TrackInfo candidate = currentTrack;
			for (int tries = 0; tries < 6 && candidate != null && candidate.equals(currentTrack); tries++) {
				candidate = tracks.get(random.nextInt(tracks.size()));
			}
			playTrack(candidate != null ? candidate : tracks.getFirst());
			return;
		}

		int idx = currentTrack != null ? findTrackIndex(tracks, currentTrack) : -1;
		int nextIdx = (idx + 1) % tracks.size();
		playTrack(tracks.get(nextIdx));
	}

	public void previousTrack() {
		List<Mp3TrackInfo> tracks = library.tracks();
		if (tracks.isEmpty()) {
			return;
		}
		if (currentTrack != null && currentPositionSeconds() > 3) {
			seek(0.0);
			return;
		}
		while (!history.isEmpty()) {
			Mp3TrackInfo prev = history.pop();
			int idx = findTrackIndex(tracks, prev);
			if (idx >= 0) {
				playTrackInternal(tracks.get(idx), 0.0, false);
				return;
			}
		}
		int idx = currentTrack != null ? findTrackIndex(tracks, currentTrack) : 0;
		int prevIdx = (idx - 1 + tracks.size()) % tracks.size();
		playTrackInternal(tracks.get(prevIdx), 0.0, false);
	}

	public void setSpeaker3dMode(boolean enabled) {
		if (config.speaker3dMode() == enabled) {
			return;
		}
		config.setSpeaker3dMode(enabled);
		config.save();

		if (!enabled) {
			// Seamlessly stop network broadcast while keeping local stereo playback running!
			stopOutgoing3dStream(true);
		} else if (currentTrack != null && alChannel != null && isServerSupporting3D()) {
			try {
				long offset = Mp3MetadataParser.findSeekFrameOffset(currentTrack, progressRatio());
				long endOffset = currentTrack.audioStartOffset() + currentTrack.audioByteLength();
				startOutgoing3dStream(currentTrack, offset, endOffset);
				if (paused) {
					streamPausedAtNanos = System.nanoTime();
					sendNetworkStateNow();
				}
			} catch (IOException e) {
				Mp3PlayerMod.LOGGER.warn("Could not start live 3D stream mid-track", e);
			}
		}
	}

	public void setVolume(float newVolume) {
		config.setVolume(newVolume);
		if (activeStreamId != null && volumeSyncCooldown <= 0) {
			sendNetworkStateNow();
			volumeSyncCooldown = 3;
		}
	}

	public static boolean hasActiveWorldConnection(Minecraft client) {
		if (client == null || client.level == null || client.player == null) {
			return false;
		}
		ClientPacketListener listener = client.getConnection();
		if (listener == null) {
			return false;
		}
		Connection connection = listener.getConnection();
		return connection != null && connection.isConnected();
	}

	public void tick(Minecraft client) {
		// Immediately stop all local & network playback if the server connection was lost or timed out
		if (!hasActiveWorldConnection(client)) {
			if (alChannel != null || decodeWorker != null || currentTrack != null || activeStreamId != null) {
				onClientDisconnect();
			}
			return;
		}

		if (volumeSyncCooldown > 0) {
			volumeSyncCooldown--;
		}
		if (activeStreamId != null && Math.abs(lastSentNetworkVolume - config.volume()) > 0.005f && volumeSyncCooldown == 0) {
			sendNetworkStateNow();
		}

		// Pump local OpenAL playback
		if (alChannel != null && decodeWorker != null) {
			float masterVol = client.options.getSoundSourceVolume(SoundSource.MASTER);
			float effectiveGain = config.volume() * masterVol;
			boolean finished = alChannel.pump(decodeWorker.outputQueue(), paused, effectiveGain, 2);
			if (finished) {
				onTrackNaturallyFinished();
				return;
			}
		}

		// Pump outgoing 4 KB frame-aligned 3D network chunks
		if (activeStreamId != null) {
			if (!isServerSupporting3D()) {
				stopOutgoing3dStream(false);
				return;
			}
			if (!paused && streamRaf != null) {
				long elapsedWallNanos = System.nanoTime() - streamStartWallNanos;
				long targetAudioNanos = elapsedWallNanos + STREAM_PREBUFFER_NANOS;

				int sentThisTick = 0;
				while (streamSentAudioNanos < targetAudioNanos && sentThisTick < MAX_CHUNKS_PER_TICK) {
					try {
						FrameChunk chunk = Mp3MetadataParser.readFrameAlignedChunk(
								streamRaf,
								streamFileOffset,
								streamEndOffset,
								Mp3Payloads.MAX_CHUNK_BYTES
						);
						if (chunk == null) {
							closeStreamRaf();
							break;
						}
						ClientPlayNetworking.send(new C2SStreamChunk(
								activeStreamId,
								streamSequence++,
								chunk.data()
						));
						streamFileOffset = chunk.nextFileOffset();
						streamSentAudioNanos += Math.max(1_000_000L, chunk.durationNanos());
						sentThisTick++;
					} catch (IOException e) {
						closeStreamRaf();
						break;
					}
				}
			}
		}
	}

	public void onClientDisconnect() {
		stopLocalAndNetwork(false);
		currentTrack = null;
		paused = false;
		seekBaseSeconds = 0.0;
		history.clear();
	}

	private void onTrackNaturallyFinished() {
		Mp3TrackInfo finishedTrack = this.currentTrack;
		stopLocalAndNetwork(true);

		List<Mp3TrackInfo> tracks = library.tracks();
		if (finishedTrack == null || tracks.isEmpty()) {
			currentTrack = null;
			return;
		}

		if (config.loopMode() == LoopMode.ONE) {
			playTrackInternal(finishedTrack, 0.0, false);
			return;
		}

		if (config.shuffle()) {
			nextTrack();
			return;
		}

		int idx = findTrackIndex(tracks, finishedTrack);
		if (idx + 1 < tracks.size()) {
			playTrack(tracks.get(idx + 1));
		} else if (config.loopMode() == LoopMode.ALL) {
			playTrack(tracks.getFirst());
		} else {
			currentTrack = null;
			paused = false;
			seekBaseSeconds = 0.0;
		}
	}

	private void startOutgoing3dStream(Mp3TrackInfo track, long startOffset, long endOffset) throws IOException {
		stopOutgoing3dStream(true);
		this.streamRaf = new RandomAccessFile(track.file().toFile(), "r");
		this.activeStreamId = UUID.randomUUID();
		this.streamFileOffset = startOffset;
		this.streamEndOffset = endOffset;
		this.streamSequence = 0;
		this.streamStartWallNanos = System.nanoTime();
		this.streamPausedAtNanos = 0L;
		this.streamSentAudioNanos = 0L;
		this.lastSentNetworkVolume = config.volume();

		ClientPlayNetworking.send(new C2SStreamStart(
				activeStreamId,
				track.fullDisplayLabel(),
				config.volume()
		));
	}

	private void sendNetworkStateNow() {
		if (activeStreamId != null && isServerSupporting3D()) {
			lastSentNetworkVolume = config.volume();
			ClientPlayNetworking.send(new C2SStreamState(activeStreamId, paused, config.volume()));
		}
	}

	private void stopOutgoing3dStream(boolean notifyServer) {
		closeStreamRaf();
		if (activeStreamId != null) {
			UUID oldId = activeStreamId;
			activeStreamId = null;
			if (notifyServer && isServerSupporting3D()) {
				ClientPlayNetworking.send(new C2SStreamStop(oldId));
			}
		}
	}

	private void closeStreamRaf() {
		if (streamRaf != null) {
			try {
				streamRaf.close();
			} catch (IOException ignored) {
			}
			streamRaf = null;
		}
	}

	private void stopLocalAndNetwork(boolean notifyServer) {
		stopOutgoing3dStream(notifyServer);
		if (decodeWorker != null) {
			decodeWorker.cancel();
			decodeWorker = null;
		}
		if (alChannel != null) {
			alChannel.close();
			alChannel = null;
		}
	}

	private static int findTrackIndex(List<Mp3TrackInfo> tracks, Mp3TrackInfo target) {
		for (int i = 0; i < tracks.size(); i++) {
			if (tracks.get(i).file().equals(target.file())) {
				return i;
			}
		}
		return -1;
	}

	private static InputStream openBoundedSlice(Mp3TrackInfo track, long startOffset, long endOffset) throws IOException {
		FileInputStream fis = new FileInputStream(track.file().toFile());
		long toSkip = Math.max(0L, startOffset);
		while (toSkip > 0) {
			long skipped = fis.skip(toSkip);
			if (skipped <= 0) {
				if (fis.read() == -1) {
					break;
				}
				toSkip--;
			} else {
				toSkip -= skipped;
			}
		}
		long maxRemaining = Math.max(0L, endOffset - startOffset);
		return new InputStream() {
			private long remaining = maxRemaining;

			@Override
			public int read() throws IOException {
				if (remaining <= 0) {
					return -1;
				}
				int b = fis.read();
				if (b != -1) {
					remaining--;
				}
				return b;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (remaining <= 0) {
					return -1;
				}
				int capped = (int) Math.min(len, remaining);
				int r = fis.read(b, off, capped);
				if (r > 0) {
					remaining -= r;
				}
				return r;
			}

			@Override
			public void close() throws IOException {
				fis.close();
			}
		};
	}
}

