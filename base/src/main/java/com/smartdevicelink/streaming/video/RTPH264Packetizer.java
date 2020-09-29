/*
 * Copyright (c) 2017, Xevo Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.smartdevicelink.streaming.video;

import androidx.annotation.RestrictTo;

import com.smartdevicelink.protocol.ProtocolMessage;
import com.smartdevicelink.protocol.enums.SessionType;
import com.smartdevicelink.session.SdlSession;
import com.smartdevicelink.streaming.AbstractPacketizer;
import com.smartdevicelink.streaming.IStreamListener;
import com.smartdevicelink.util.DebugTool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/*
 * Note for testing.
 * The RTP stream generated by this packetizer can be tested with GStreamer (1.4 or later).
 * Assuming that "VideoStreamPort" is configured as 5050 in smartDeviceLink.ini, here is the
 * GStreamer pipeline that receives the stream, decode it and render it:
 *
 * $ gst-launch-1.0 souphttpsrc location=http://127.0.0.1:5050 ! "application/x-rtp-stream" ! rtpstreamdepay ! "application/x-rtp,media=(string)video,clock-rate=90000,encoding-name=(string)H264" ! rtph264depay ! "video/x-h264, stream-format=(string)avc, alignment=(string)au" ! avdec_h264 ! autovideosink sync=false
 */

/**
 * This class receives H.264 byte stream (in Annex-B format), parses it, construct RTP packets
 * from it based on RFC 6184, then frame the packets based on RFC 4571.
 * The primary purpose of using RTP is to carry timestamp information along with the data.
 *
 * @author Sho Amano
 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class RTPH264Packetizer extends AbstractPacketizer implements IVideoStreamListener, Runnable {

    private static final String TAG = "RTPH264Packetizer";

    // Approximate size of data that mOutputQueue can hold in bytes.
    // By adding a buffer, we accept underlying transport being stuck for a short time. By setting
    // a limit of the buffer size, we avoid buffer overflows when underlying transport is too slow.
    private static final int MAX_QUEUE_SIZE = 256 * 1024;

    private static final int FRAME_LENGTH_LEN = 2;
    private static final int MAX_RTP_PACKET_SIZE = 65535;  // because length field is two bytes (RFC 4571)
    private static final int RTP_HEADER_LEN = 12;
    private static final byte DEFAULT_RTP_PAYLOAD_TYPE = 96;
    private static final int FU_INDICATOR_LEN = 1;
    private static final int FU_HEADER_LEN = 1;
    private static final byte TYPE_FU_A = 28;

    // To align with StreamPacketizer class
    private final static int TLS_MAX_RECORD_SIZE = 16384;
    private final static int TLS_RECORD_HEADER_SIZE = 5;
    private final static int TLS_RECORD_MES_AUTH_CDE_SIZE = 32;
    private final static int TLS_MAX_RECORD_PADDING_SIZE = 256;

    private final static int MAX_DATA_SIZE_FOR_ENCRYPTED_SERVICE =
            TLS_MAX_RECORD_SIZE - TLS_RECORD_HEADER_SIZE - TLS_RECORD_MES_AUTH_CDE_SIZE - TLS_MAX_RECORD_PADDING_SIZE;

    private boolean mServiceProtected;
    private Thread mThread;
    private BlockingQueue<ByteBuffer> mOutputQueue;
    private volatile boolean mPaused;
    private boolean mWaitForIDR;
    private NALUnitReader mNALUnitReader;
    private byte mPayloadType = 0;
    private int mSSRC = 0;
    private char mSequenceNum = 0;
    private int mInitialPTS = 0;

    /**
     * Constructor
     *
     * @param streamListener The listener which this packetizer outputs SDL frames to
     * @param serviceType    The value of "Service Type" field in SDL frames
     * @param sessionID      The value of "Session ID" field in SDL frames
     * @param session        The SdlSession instance that this packetizer belongs to
     */
    public RTPH264Packetizer(IStreamListener streamListener,
                             SessionType serviceType, byte sessionID, SdlSession session) throws IOException {

        super(streamListener, null, serviceType, sessionID, session);

        mServiceProtected = session.isServiceProtected(_serviceType);

        bufferSize = (int) this._session.getMtu(SessionType.NAV);
        if (bufferSize == 0) {
            // fail safe
            bufferSize = MAX_DATA_SIZE_FOR_ENCRYPTED_SERVICE;
        }
        if (mServiceProtected && bufferSize > MAX_DATA_SIZE_FOR_ENCRYPTED_SERVICE) {
            bufferSize = MAX_DATA_SIZE_FOR_ENCRYPTED_SERVICE;
        }

        mOutputQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE / bufferSize);
        mNALUnitReader = new NALUnitReader();
        mPayloadType = DEFAULT_RTP_PAYLOAD_TYPE;

        Random r = new Random();
        mSSRC = r.nextInt();

        // initial value of the sequence number and timestamp should be random ([5.1] in RFC3550)
        mSequenceNum = (char) r.nextInt(65536);
        mInitialPTS = r.nextInt();
    }

    /**
     * Sets the Payload Type (PT) of RTP header field.
     * <p>
     * Use this method if PT needs to be specified. The value should be between 0 and 127.
     * Otherwise, a default value (96) is used.
     *
     * @param type A value indicating the Payload Type
     */
    public void setPayloadType(byte type) {
        if (type >= 0 && type <= 127) {
            mPayloadType = type;
        } else {
            mPayloadType = DEFAULT_RTP_PAYLOAD_TYPE;
        }
    }

    /**
     * Sets the SSRC of RTP header field.
     * <p>
     * Use this method if SSRC needs to be specified. Otherwise, a random value is generated and
     * used.
     *
     * @param ssrc An integer value representing SSRC
     */
    public void setSSRC(int ssrc) {
        mSSRC = ssrc;
    }

    /**
     * Starts this packetizer.
     * <p>
     * It is recommended that the video encoder is started after the packetizer is started.
     */
    @Override
    public void start() throws IOException {
        if (mThread != null) {
            return;
        }

        if (mOutputQueue != null) {
            mOutputQueue.clear();
        }

        mThread = new Thread(this);
        mThread.start();
    }

    /**
     * Stops this packetizer.
     * <p>
     * It is recommended that the video encoder is stopped prior to the packetizer.
     */
    @Override
    public void stop() {
        if (mThread == null) {
            return;
        }

        mThread.interrupt();
        mThread = null;

        mPaused = false;
        mWaitForIDR = false;
        mOutputQueue.clear();
    }

    /**
     * Pauses this packetizer.
     * <p>
     * This pauses the packetizer but does not pause the video encoder.
     */
    @Override
    public void pause() {
        mPaused = true;
    }

    /**
     * Resumes this packetizer.
     */
    @Override
    public void resume() {
        mWaitForIDR = true;
        mPaused = false;
    }

    /**
     * The thread routine.
     */
    public void run() {

        while (mThread != null && !mThread.isInterrupted()) {
            ByteBuffer frame;
            try {
                frame = mOutputQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            while (frame.hasRemaining()) {
                int len = Math.min(frame.remaining(), bufferSize);

                ProtocolMessage pm = new ProtocolMessage();
                pm.setSessionID(_rpcSessionID);
                pm.setSessionType(_serviceType);
                pm.setFunctionID(0);
                pm.setCorrID(0);
                pm.setData(frame.array(), frame.arrayOffset() + frame.position(), len);
                pm.setPayloadProtected(mServiceProtected);

                _streamListener.sendStreamPacket(pm);

                frame.position(frame.position() + len);
            }
        }

        // XXX: This is added to sync with StreamPacketizer. Actually it shouldn't be here since
        // it's confusing that a packetizer takes care of End Service request.
        if (_session != null) {
            _session.endService(_serviceType);
        }
    }

    /**
     * Called by the app and encoder.
     *
     * @see IVideoStreamListener#sendFrame(byte[], int, int, long)
     */
    @Override
    public void sendFrame(byte[] data, int offset, int length, long presentationTimeUs)
            throws ArrayIndexOutOfBoundsException {
        mNALUnitReader.init(data, offset, length);
        onEncoderOutput(mNALUnitReader, presentationTimeUs);
    }

    /**
     * Called by the app and encoder.
     *
     * @see IVideoStreamListener#sendFrame(ByteBuffer, long)
     */
    @Override
    public void sendFrame(ByteBuffer data, long presentationTimeUs) {
        mNALUnitReader.init(data);
        onEncoderOutput(mNALUnitReader, presentationTimeUs);
    }

    private void onEncoderOutput(NALUnitReader nalUnitReader, long ptsInUs) {
        if (mPaused) {
            return;
        }

        ByteBuffer nalUnit;

        while ((nalUnit = nalUnitReader.getNalUnit()) != null) {
            if (mWaitForIDR) {
                if (isIDR(nalUnit)) {
                    mWaitForIDR = false;
                } else {
                    continue;
                }
            }
            outputRTPFrames(nalUnit, ptsInUs, nalUnitReader.hasConsumedAll());
        }
    }

    private boolean outputRTPFrames(ByteBuffer nalUnit, long ptsInUs, boolean isLast) {
        if ((mThread == null || mThread.isInterrupted())) {
            DebugTool.logError(TAG, "Dropping potential buffer because consumer thread is not alive");
            return false;
        }

        if (RTP_HEADER_LEN + nalUnit.remaining() > MAX_RTP_PACKET_SIZE) {
            // Split into multiple Fragmentation Units ([5.8] in RFC 6184)
            byte firstByte = nalUnit.get();
            boolean firstFragment = true;
            boolean lastFragment = false;

            while (nalUnit.remaining() > 0 && mThread != null && !mThread.isInterrupted()) {
                int payloadLength = MAX_RTP_PACKET_SIZE - (RTP_HEADER_LEN + FU_INDICATOR_LEN + FU_HEADER_LEN);
                if (nalUnit.remaining() <= payloadLength) {
                    payloadLength = nalUnit.remaining();
                    lastFragment = true;
                }

                ByteBuffer frame = allocateRTPFrame(FU_INDICATOR_LEN + FU_HEADER_LEN + payloadLength,
                        false, isLast, ptsInUs);
                // FU indicator
                frame.put((byte) ((firstByte & 0xE0) | TYPE_FU_A));
                // FU header
                frame.put((byte) ((firstFragment ? 0x80 : lastFragment ? 0x40 : 0) | (firstByte & 0x1F)));
                // FU payload
                frame.put(nalUnit.array(), nalUnit.position(), payloadLength);
                nalUnit.position(nalUnit.position() + payloadLength);
                frame.flip();

                try {
                    mOutputQueue.put(frame);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }

                firstFragment = false;
            }
        } else {
            // Use Single NAL Unit Packet ([5.6] in RFC 6184)
            ByteBuffer frame = allocateRTPFrame(nalUnit.remaining(), false, isLast, ptsInUs);
            frame.put(nalUnit);
            frame.flip();

            try {
                mOutputQueue.put(frame);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return true;
    }

    private ByteBuffer allocateRTPFrame(int rtpPayloadLen,
                                        boolean hasPadding, boolean isLast, long ptsInUs) {
        if (rtpPayloadLen <= 0) {
            throw new IllegalArgumentException("Invalid rtpPayloadLen value: " + rtpPayloadLen);
        }
        if (ptsInUs < 0) {
            throw new IllegalArgumentException("Invalid ptsInUs value: " + ptsInUs);
        }

        int packetLength = RTP_HEADER_LEN + rtpPayloadLen;
        if (packetLength > MAX_RTP_PACKET_SIZE) {
            throw new IllegalArgumentException("Invalid rtpPayloadLen value: " + rtpPayloadLen);
        }
        int ptsIn90kHz = (int) (ptsInUs * 9 / 100) + mInitialPTS;

        ByteBuffer frame = ByteBuffer.allocate(FRAME_LENGTH_LEN + packetLength);
        frame.order(ByteOrder.BIG_ENDIAN);
        frame.putShort((short) packetLength);

        // Version = 2, Padding = hasPadding, Extension = 0, CSRC count = 0
        frame.put((byte) (0x80 | (hasPadding ? 0x20 : 0)))
                // Marker = isLast, Payload type = mPayloadType
                .put((byte) ((isLast ? 0x80 : 0) | (mPayloadType & 0x7F)))
                .putChar(mSequenceNum)
                .putInt(ptsIn90kHz)
                .putInt(mSSRC);

        if (frame.position() != FRAME_LENGTH_LEN + RTP_HEADER_LEN) {
            throw new RuntimeException("Data size in ByteBuffer mismatch");
        }

        mSequenceNum++;
        return frame;
    }

    private static boolean isIDR(ByteBuffer nalUnit) {
        if (nalUnit == null || !nalUnit.hasRemaining()) {
            throw new IllegalArgumentException("Invalid nalUnit arg");
        }

        byte nalUnitType = (byte) (nalUnit.get(nalUnit.position()) & 0x1F);
        return nalUnitType == 5;
    }


    private static int[] SKIP_TABLE = new int[256];

    static {
        // Sunday's quick search algorithm is used to find the start code.
        // Prepare the table (SKIP_TABLE[0] = 2, SKIP_TABLE[1] = 1 and other elements will be 4).
        byte[] NAL_UNIT_START_CODE = {0, 0, 1};
        int searchStringLen = NAL_UNIT_START_CODE.length;
        for (int i = 0; i < SKIP_TABLE.length; i++) {
            SKIP_TABLE[i] = searchStringLen + 1;
        }
        for (int i = 0; i < searchStringLen; i++) {
            SKIP_TABLE[NAL_UNIT_START_CODE[i] & 0xFF] = searchStringLen - i;
        }
    }

    private class NALUnitReader {
        private byte[] mData;
        private int mOffset;
        private int mLimit;

        NALUnitReader() {
        }

        void init(byte[] data) {
            mData = data;
            mOffset = 0;
            mLimit = data.length;
        }

        void init(byte[] data, int offset, int length) throws ArrayIndexOutOfBoundsException {
            if (offset < 0 || offset > data.length || length <= 0 || offset + length > data.length) {
                throw new ArrayIndexOutOfBoundsException();
            }
            mData = data;
            mOffset = offset;
            mLimit = offset + length;
        }

        void init(ByteBuffer data) {
            if (data == null || data.remaining() == 0) {
                mData = null;
                mOffset = 0;
                mLimit = 0;
                return;
            }

            if (data.hasArray()) {
                mData = data.array();
                mOffset = data.position() + data.arrayOffset();
                mLimit = mOffset + data.remaining();

                // mark the buffer as consumed
                data.position(data.position() + data.remaining());
            } else {
                byte[] buffer = new byte[data.remaining()];
                data.get(buffer);

                mData = buffer;
                mOffset = 0;
                mLimit = buffer.length;
            }
        }

        ByteBuffer getNalUnit() {
            if (hasConsumedAll()) {
                return null;
            }

            int pos = mOffset;
            int start = -1;

            while (mLimit - pos >= 3) {
                if (mData[pos] == 0 && mData[pos + 1] == 0 && mData[pos + 2] == 1) {
                    if (start != -1) {
                        // We've found a start code, a NAL unit and then another start code.
                        mOffset = pos;
                        // remove 0x00s in front of the start code
                        while (pos > start && mData[pos - 1] == 0) {
                            pos--;
                        }
                        if (pos > start) {
                            return ByteBuffer.wrap(mData, start, pos - start);
                        } else {
                            // No NAL unit between two start codes?! Forget it and search for
                            // another start code.
                            pos = mOffset;
                        }
                    }
                    // This is the first start code.
                    pos += 3;
                    start = pos;
                } else {
                    try {
                        pos += SKIP_TABLE[mData[pos + 3] & 0xFF];
                    } catch (ArrayIndexOutOfBoundsException e) {
                        break;
                    }
                }
            }

            mOffset = mLimit;
            if (start != -1 && mLimit > start) {
                // We've found a start code and then reached to the end of array.
                return ByteBuffer.wrap(mData, start, mLimit - start);
            }
            // A start code was not found
            return null;
        }

        boolean hasConsumedAll() {
            return (mData == null) || (mLimit - mOffset < 4);
        }
    }
}
