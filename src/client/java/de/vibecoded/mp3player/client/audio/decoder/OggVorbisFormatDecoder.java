package de.vibecoded.mp3player.client.audio.decoder;

import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import de.vibecoded.mp3player.mp3.OggPacketReader;
import java.io.IOException;

/**
 * Pure-Java Ogg Vorbis streaming decoder powered by Minecraft's native JOrbis library,
 * with automatic delegation to OpusFormatDecoder if an .ogg file contains an Opus stream,
 * plus instant O(1) Ogg page-resync seeking.
 */
public final class OggVorbisFormatDecoder implements AudioTrackDecoder {
	private final OggPacketReader ogg;
	private final Info vi = new Info();
	private final Comment vc = new Comment();
	private final DspState vd = new DspState();
	private final Block vb = new Block(vd);
	private final Packet op = new Packet();

	private final int sampleRate;
	private final int srcChannels;
	private final int outChannels;
	private final float[][][] pcmHolder = new float[1][][];
	private final int[] pcmIndex;
	private long packetCounter = 3L;

	public static AudioTrackDecoder openOggOrOpus(Mp3TrackInfo track, double seekRatio) throws IOException {
		try (OggPacketReader probe = new OggPacketReader(track.file())) {
			byte[] first = probe.readNextPacket();
			if (first != null && first.length >= 19
					&& first[0] == 'O' && first[1] == 'p' && first[2] == 'u' && first[3] == 's'
					&& first[4] == 'H' && first[5] == 'e' && first[6] == 'a' && first[7] == 'd') {
				return new OpusFormatDecoder(track, seekRatio);
			}
		}
		return new OggVorbisFormatDecoder(track, seekRatio);
	}

	public OggVorbisFormatDecoder(Mp3TrackInfo track, double seekRatio) throws IOException {
		this.ogg = new OggPacketReader(track.file());
		try {
			vi.init();
			vc.init();

			for (int i = 0; i < 3; i++) {
				byte[] hdr = ogg.readNextPacket();
				if (hdr == null) {
					throw new IOException("Truncated Ogg Vorbis header #" + i + " in " + track.file());
				}
				op.packet_base = hdr;
				op.packet = 0;
				op.bytes = hdr.length;
				op.b_o_s = (i == 0) ? 1 : 0;
				op.e_o_s = 0;
				op.granulepos = 0;
				op.packetno = i;

				if (vi.synthesis_headerin(vc, op) < 0) {
					throw new IOException("Invalid Ogg Vorbis header packet #" + i + " in " + track.file());
				}
			}

			if (vi.channels < 1 || vi.rate <= 0) {
				throw new IOException("Invalid Ogg Vorbis audio parameters in " + track.file());
			}

			vd.synthesis_init(vi);
			vb.init(vd);

			this.sampleRate = vi.rate;
			this.srcChannels = vi.channels;
			this.outChannels = Math.min(2, vi.channels);
			this.pcmIndex = new int[vi.channels];

			long audioStartOffset = ogg.currentOffset();
			long fileLength = ogg.fileLength();
			if (seekRatio > 0.0001 && fileLength > audioStartOffset) {
				long targetByte = audioStartOffset + (long) ((fileLength - audioStartOffset) * seekRatio);
				ogg.seekAndResync(targetByte);
			}
		} catch (IOException | RuntimeException e) {
			close();
			throw e;
		}
	}

	@Override
	public DecodedPcm decodeNext() throws Exception {
		while (true) {
			int framesAvailable = vd.synthesis_pcmout(pcmHolder, pcmIndex);
			if (framesAvailable > 0) {
				float[][] pcm = pcmHolder[0];
				int frames = Math.min(framesAvailable, 4096);
				short[] out = new short[frames * outChannels];
				int outPos = 0;

				if (outChannels == 1) {
					float[] c0 = pcm[0];
					int base0 = pcmIndex[0];
					for (int i = 0; i < frames; i++) {
						out[outPos++] = floatToShort(c0[base0 + i]);
					}
				} else {
					float[] c0 = pcm[0];
					float[] c1 = pcm[1];
					int base0 = pcmIndex[0];
					int base1 = pcmIndex[1];
					for (int i = 0; i < frames; i++) {
						out[outPos++] = floatToShort(c0[base0 + i]);
						out[outPos++] = floatToShort(c1[base1 + i]);
					}
				}

				vd.synthesis_read(frames);
				return new DecodedPcm(out, outPos, sampleRate, outChannels);
			}

			byte[] pkt = ogg.readNextPacket();
			if (pkt == null) {
				return null; // EOF
			}
			if (pkt.length == 0) {
				continue;
			}

			op.packet_base = pkt;
			op.packet = 0;
			op.bytes = pkt.length;
			op.b_o_s = 0;
			op.e_o_s = 0;
			op.granulepos = -1L;
			op.packetno = packetCounter++;

			if (vb.synthesis(op) == 0) {
				vd.synthesis_blockin(vb);
			}
		}
	}

	private static short floatToShort(float v) {
		if (Float.isNaN(v)) {
			return 0;
		}
		int scaled = Math.round(v * 32767.0f);
		if (scaled > 32767) {
			return 32767;
		}
		if (scaled < -32768) {
			return -32768;
		}
		return (short) scaled;
	}

	@Override
	public void close() {
		try {
			vb.clear();
			vd.clear();
			vi.clear();
		} catch (Exception ignored) {
		}
		try {
			ogg.close();
		} catch (IOException ignored) {
		}
	}
}

