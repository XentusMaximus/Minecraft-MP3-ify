package de.vibecoded.mp3player.client.audio.decoder;

import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import de.vibecoded.mp3player.mp3.OggPacketReader;
import io.github.jaredmdobson.OpusDecoder;
import io.github.jaredmdobson.OpusException;
import java.io.IOException;

/**
 * Pure-Java Ogg Opus streaming decoder (RFC 7845 + Concentus OpusDecoder)
 * with pre-skip trimming, Q7.8 output gain support, and O(1) Ogg page-resync seeking.
 */
public final class OpusFormatDecoder implements AudioTrackDecoder {
	private static final int OPUS_SAMPLE_RATE = 48000;
	private static final int MAX_OPUS_FRAME_SAMPLES = 5760; // 120ms max Opus packet @ 48 kHz

	private final OggPacketReader ogg;
	private final OpusDecoder decoder;
	private final int channels;
	private final short[] decodeBuf;
	private final float gainMultiplier;
	private final boolean hasGain;
	private int preSkipRemaining;

	public OpusFormatDecoder(Mp3TrackInfo track, double seekRatio) throws IOException {
		this.ogg = new OggPacketReader(track.file());
		try {
			byte[] head = ogg.readNextPacket();
			if (head == null || head.length < 19 || !startsWithAscii(head, "OpusHead")) {
				throw new IOException("Invalid OpusHead packet in " + track.file());
			}

			int streamChannels = head[9] & 0xFF;
			int mappingFamily = head[18] & 0xFF;
			if (streamChannels < 1 || (streamChannels > 2 && mappingFamily != 0)) {
				throw new IOException("Unsupported multichannel Opus layout (" + streamChannels + " ch)");
			}
			this.channels = Math.min(2, streamChannels);
			this.preSkipRemaining = OggPacketReader.readUInt16LE(head, 10);

			short outputGainQ8 = OggPacketReader.readInt16LE(head, 16);
			if (outputGainQ8 != 0) {
				this.gainMultiplier = (float) Math.pow(10.0, outputGainQ8 / (20.0 * 256.0));
				this.hasGain = true;
			} else {
				this.gainMultiplier = 1.0f;
				this.hasGain = false;
			}

			// Consume OpusTags comment header packet
			byte[] tags = ogg.readNextPacket();
			if (tags == null) {
				throw new IOException("Missing OpusTags packet in " + track.file());
			}

			try {
				this.decoder = new OpusDecoder(OPUS_SAMPLE_RATE, this.channels);
			} catch (OpusException e) {
				throw new IOException("Failed to initialize Concentus OpusDecoder", e);
			}
			this.decodeBuf = new short[MAX_OPUS_FRAME_SAMPLES * this.channels];

			long audioStartOffset = ogg.currentOffset();
			long fileLength = ogg.fileLength();
			if (seekRatio > 0.0001 && fileLength > audioStartOffset) {
				long targetByte = audioStartOffset + (long) ((fileLength - audioStartOffset) * seekRatio);
				ogg.seekAndResync(targetByte);
				this.preSkipRemaining = 0;
				this.decoder.resetState();
			}
		} catch (IOException | RuntimeException e) {
			try {
				ogg.close();
			} catch (IOException ignored) {
			}
			throw e;
		}
	}

	@Override
	public DecodedPcm decodeNext() throws Exception {
		while (true) {
			byte[] packet = ogg.readNextPacket();
			if (packet == null) {
				return null; // EOF
			}
			if (packet.length == 0 || startsWithAscii(packet, "OpusTags") || startsWithAscii(packet, "OpusHead")) {
				continue;
			}

			int decodedFrames;
			try {
				decodedFrames = decoder.decode(packet, 0, packet.length, decodeBuf, 0, MAX_OPUS_FRAME_SAMPLES, false);
			} catch (OpusException | IllegalArgumentException ignored) {
				// Skip single damaged Opus packet after seek or stream glitch
				continue;
			}

			if (decodedFrames <= 0) {
				continue;
			}

			int startFrame = 0;
			if (preSkipRemaining > 0) {
				int skip = Math.min(preSkipRemaining, decodedFrames);
				preSkipRemaining -= skip;
				startFrame = skip;
				if (startFrame >= decodedFrames) {
					continue;
				}
			}

			int validFrames = decodedFrames - startFrame;
			int validShorts = validFrames * channels;
			int srcOffset = startFrame * channels;
			short[] out = new short[validShorts];

			if (hasGain) {
				for (int i = 0; i < validShorts; i++) {
					int scaled = Math.round(decodeBuf[srcOffset + i] * gainMultiplier);
					out[i] = (short) Math.max(-32768, Math.min(32767, scaled));
				}
			} else {
				System.arraycopy(decodeBuf, srcOffset, out, 0, validShorts);
			}

			return new DecodedPcm(out, validShorts, OPUS_SAMPLE_RATE, channels);
		}
	}

	private static boolean startsWithAscii(byte[] b, String ascii) {
		if (b.length < ascii.length()) {
			return false;
		}
		for (int i = 0; i < ascii.length(); i++) {
			if ((b[i] & 0xFF) != ascii.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public void close() {
		try {
			ogg.close();
		} catch (IOException ignored) {
		}
	}
}

