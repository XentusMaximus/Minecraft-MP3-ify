package de.vibecoded.mp3player.client.audio.decoder;

import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;
import net.sourceforge.jaad.SampleBuffer;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.mp4.MP4Container;
import net.sourceforge.jaad.mp4.MP4InputStream;
import net.sourceforge.jaad.mp4.api.AudioTrack;
import net.sourceforge.jaad.mp4.api.Frame;
import net.sourceforge.jaad.mp4.api.Movie;
import net.sourceforge.jaad.mp4.api.Track;
import net.sourceforge.jaad.mp4.od.DecoderSpecificInfo;

/**
 * Pure-Java M4A / MP4 (AAC-LC / HE-AAC / SBR / PS) streaming decoder powered by JAAD,
 * supporting O(1) MP4 sample-table seeking.
 */
public final class M4aFormatDecoder implements AudioTrackDecoder {
	private final RandomAccessFile raf;
	private final MP4InputStream mp4In;
	private final AudioTrack audioTrack;
	private final Decoder aacDecoder;
	private final SampleBuffer sampleBuffer;

	public M4aFormatDecoder(Mp3TrackInfo track, double seekRatio) throws IOException {
		this.raf = new RandomAccessFile(track.file().toFile(), "r");
		this.mp4In = MP4InputStream.open(raf);
		try {
			MP4Container container = new MP4Container(mp4In);
			Movie movie = container.getMovie();
			if (movie == null) {
				throw new IOException("M4A container contains no Movie box: " + track.file());
			}

			List<Track> tracks = movie.getTracks(AudioTrack.AudioCodec.AAC);
			if (tracks == null || tracks.isEmpty()) {
				throw new IOException("M4A file contains no AAC audio track: " + track.file());
			}

			this.audioTrack = (AudioTrack) tracks.getFirst();
			DecoderSpecificInfo dsi = audioTrack.getDecoderSpecificInfo();
			if (dsi == null || dsi.getData() == null || dsi.getData().length == 0) {
				throw new IOException("Missing AAC DecoderSpecificInfo in " + track.file());
			}

			this.aacDecoder = Decoder.create(dsi.getData());
			java.util.logging.Logger.getLogger("jaad.aac.Decoder").setLevel(java.util.logging.Level.OFF);
			this.sampleBuffer = new SampleBuffer();
			this.sampleBuffer.setBigEndian(false);

			if (seekRatio > 0.0001) {
				double duration = movie.getDuration() > 0.0 ? movie.getDuration() : track.durationSeconds();
				if (duration > 0.0) {
					audioTrack.seek(duration * seekRatio);
				}
			}
		} catch (IOException | RuntimeException e) {
			close();
			throw e;
		}
	}

	@Override
	public DecodedPcm decodeNext() throws Exception {
		while (audioTrack.hasMoreFrames()) {
			Frame frame = audioTrack.readNextFrame();
			if (frame == null) {
				return null;
			}
			byte[] data = frame.getData();
			if (data == null || data.length == 0) {
				continue;
			}

			try {
				aacDecoder.decodeFrame(data, sampleBuffer);
			} catch (Exception ignored) {
				// Skip single damaged AAC access unit
				continue;
			}

			byte[] pcmBytes = sampleBuffer.getData();
			int sr = sampleBuffer.getSampleRate();
			int srcChannels = sampleBuffer.getChannels();
			if (pcmBytes == null || pcmBytes.length < 2 || sr <= 0 || srcChannels <= 0) {
				continue;
			}

			int totalFrames = pcmBytes.length / (2 * srcChannels);
			if (totalFrames <= 0) {
				continue;
			}

			int outChannels = Math.min(2, srcChannels);
			short[] pcm = new short[totalFrames * outChannels];
			int outIdx = 0;

			for (int f = 0; f < totalFrames; f++) {
				int frameByteBase = f * srcChannels * 2;
				for (int ch = 0; ch < outChannels; ch++) {
					int bOff = frameByteBase + ch * 2;
					pcm[outIdx++] = (short) ((pcmBytes[bOff] & 0xFF) | (pcmBytes[bOff + 1] << 8));
				}
			}

			return new DecodedPcm(pcm, outIdx, sr, outChannels);
		}
		return null;
	}

	@Override
	public void close() {
		try {
			mp4In.close();
		} catch (IOException ignored) {
		}
		try {
			raf.close();
		} catch (IOException ignored) {
		}
	}
}
