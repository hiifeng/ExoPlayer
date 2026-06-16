/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.extractor.ts;

import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.ParsableBitArray;
import com.google.android.exoplayer2.util.ParsableByteArray;

/**
 * Parses a continuous AV3A byte stream and extracts individual audio frames.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See 
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
public final class Av3aReader implements ElementaryStreamReader {

  private static final int HEADER_SIZE = 7;

  private static final int STATE_FINDING_SYNC = 0;
  private static final int STATE_READING_HEADER = 1;
  private static final int STATE_READING_SAMPLE = 2;

  @Nullable private final String language;
  @Nullable private TrackOutput output;

  private int state;
  private int bytesRead;
  private long timeUs;
  private int frameSize;
  private boolean hasOutputFormat;

  private final ParsableByteArray headerScratch;
  private final ParsableBitArray headerBitArray;

  public Av3aReader(@Nullable String language) {
    this.language = language;
    headerScratch = new ParsableByteArray(new byte[HEADER_SIZE]);
    headerBitArray = new ParsableBitArray();
    state = STATE_FINDING_SYNC;
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    state = STATE_FINDING_SYNC;
    bytesRead = 0;
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, PesReader.TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if (pesTimeUs != C.TIME_UNSET) {
      timeUs = pesTimeUs;
    }
  }

  @Override
  public void consume(ParsableByteArray data) {
    while (data.bytesLeft() > 0) {
      switch (state) {
        case STATE_FINDING_SYNC:
          if (skipToNextSync(data)) {
            state = STATE_READING_HEADER;
          }
          break;
        case STATE_READING_HEADER:
          int bytesToRead = Math.min(data.bytesLeft(), HEADER_SIZE - bytesRead);
          data.readBytes(headerScratch.getData(), bytesRead, bytesToRead);
          bytesRead += bytesToRead;
          if (bytesRead == HEADER_SIZE) {
            headerBitArray.reset(headerScratch.getData());
            frameSize = parseFrameSize(headerBitArray);
            if (frameSize <= 0) {
              state = STATE_FINDING_SYNC;
              bytesRead = 0;
              break;
            }
            if (!hasOutputFormat) {
              outputFormat();
            }
            output.sampleData(
                new ParsableByteArray(headerScratch.getData(), HEADER_SIZE), HEADER_SIZE);
            state = STATE_READING_SAMPLE;
          }
          break;
        case STATE_READING_SAMPLE:
          int bytesLeft = frameSize - bytesRead;
          int bytesAvailable = Math.min(data.bytesLeft(), bytesLeft);
          output.sampleData(data, bytesAvailable);
          bytesRead += bytesAvailable;
          if (bytesRead == frameSize) {
            if (timeUs != C.TIME_UNSET) {
              output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null);
              timeUs += 46440;
            }
            state = STATE_FINDING_SYNC;
            bytesRead = 0;
          }
          break;
        default:
          break;
      }
    }
  }

  @Override
  public void packetFinished() {
    // do nothing
  }

  private boolean skipToNextSync(ParsableByteArray data) {
    byte[] buf = data.getData();
    int pos = data.getPosition();
    int limit = data.limit();
    while (pos < limit - 1) {
      if ((buf[pos] & 0xFF) == 0xFF && (buf[pos + 1] & 0xF0) == 0xF0) {
        headerScratch.getData()[0] = buf[pos];
        headerScratch.getData()[1] = buf[pos + 1];
        data.setPosition(pos + 2);
        bytesRead = 2;
        return true;
      }
      pos++;
    }
    data.setPosition(limit);
    return false;
  }

  private int parseFrameSize(ParsableBitArray bits) {
    try {
      bits.skipBits(12); // syncword
      bits.skipBits(4);  // audio_codec_id
      bits.skipBits(3);  // anc_data_index
      bits.skipBits(3);  // nn_type
      bits.skipBits(2);  // coding_profile
      bits.skipBits(4);  // sampling_freq_id
      bits.skipBits(7);  // channel_number_index
      bits.skipBits(2);  // resolution
      int frameLength = bits.readBits(16);
      return frameLength > 0 ? frameLength : -1;
    } catch (Exception e) {
      return -1;
    }
  }

  private void outputFormat() {
    if (output == null) {
      return;
    }
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AV3A)
            .setLanguage(language)
            .build();
    output.format(format);
    hasOutputFormat = true;
  }
}
