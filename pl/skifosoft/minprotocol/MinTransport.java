package pl.skifosoft.minprotocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * Main class implementing MIN (Microcontroller Interconnect Network) protocol.
 */
public class MinTransport {

    private static enum State {
        SEARCHING_FOR_SOF,
        RECEIVING_ID_CONTROL,
        RECEIVING_LENGTH,
        RECEIVING_SEQ,
        RECEIVING_PAYLOAD,
        RECEIVING_CHECKSUM_3,
        RECEIVING_CHECKSUM_2,
        RECEIVING_CHECKSUM_1,
        RECEIVING_CHECKSUM_0,
        RECEIVING_EOF;
    }

    private static final int None = -1;
    private static final byte HEADER_BYTE = (byte) 0xaa;
    private static final byte STUFF_BYTE = (byte) 0x55;

    private static final byte EOF_BYTE = (byte) 0x55;
    private static final byte ACK = (byte) 0xff;

    private static final byte RESET = (byte) 0xfe;

    private final int transport_fifo_size = 100;
    private int ack_retransmit_timeout_ms = 25;
    private final int max_window_size = 8;
    private final int idle_timeout_ms = 30000;
    private int frame_retransmit_timeout_ms = 50;
    private final int rx_window_size = 16;

    // Stats about the link
    //private final int longest_transport_fifo = 0;
    private int dropped_frames = 0;
    private int spurious_acks = 0;
    //private final int mismatched_acks = 0;
    //private final int duplicate_frames = 0;
    //private final int retransmitted_frames = 0;
    private int resets_received = 0;
    private int sequence_mismatch_drops = 0;

    // State of transport FIFO
    ArrayList<MinFrame> transport_fifo = new ArrayList<MinFrame>();
    private long last_sent_ack_time_ms = None;
    private long last_received_anything_ms = None;
    private long last_received_frame_ms = None;
    private long last_sent_frame_ms = None;

    // # State for receiving a MIN frame
    private ByteArrayOutputStream rx_frame_buf;
    private int rx_header_bytes_seen = 0;
    private State rx_frame_state = State.SEARCHING_FOR_SOF;
    private int rx_frame_checksum = 0;
    private byte rx_frame_id_control = 0;
    private byte rx_frame_seq = 0;
    private byte rx_frame_length = 0;
    private byte rx_control = 0;

    ArrayList<MinFrame> rx_list = new ArrayList<MinFrame>();
    HashMap<Integer, MinFrame> stashed_rx_dict = new HashMap<Integer, MinFrame>();

    // # Sequence numbers
    private int rn = 0; // # Sequence number expected to be received next
    private int sn_min = 0; // # Sequence number of first frame currently in the sending window
    private int sn_max = 0; // # Next sequence number to use for sending a frame

    // # NACK status
    private int nack_outstanding = None;

    private final MinSerialInterface serialInterface;

    /**
     * Entry point constructor
     *
     * @param serialInterface
     */
    public MinTransport(final MinSerialInterface serialInterface) {
        this.serialInterface = serialInterface;
        transport_fifo_reset();
    }

    /**
     * Frames that dont't receive ACK yet would be retransmitted after that timeout.
     * You want that value considerably greater than polling frequency
     * otherwise received ACK would be probably to late and frame would be
     * retransmitted for no reason.
     * Default is 50 ms.
     *
     * @param retransmitTimeoutMs retransmit timeout in milliseconds
     */
    public void setRetransmitTimeout(int retransmitTimeoutMs) {
        frame_retransmit_timeout_ms = (retransmitTimeoutMs >= 0) ? retransmitTimeoutMs : 0;
    }


    /**
     * Timeout defining how often host shall send keep-alive packets.
     * Note: repeated ACK frame serves that purpose
     * Set that reasonably high, not to overflow the other end.
     * Minimal and default value is 25 ms.
     *
     * @param keepAliveTimeout timeout in milliseconds
     */
    public void setKeepAliveTimeout(int keepAliveTimeoutMs) {
        ack_retransmit_timeout_ms = (keepAliveTimeoutMs > 25) ? keepAliveTimeoutMs : 25;
    }

    /**
     * Sends transport reset request to another end
     * and resets internal transport state.
     */
    public void transportReset() {
        send_reset();
        send_reset();

        transport_fifo_reset();
        rx_reset();
    }

    /**
     * Directly sends non-transport frame. Such frames are not queued nor retransmitted in case of error.
     *
     * @param minId user defined id of the frame (must be 0 - 63 range)
     * @param payload data to send
     */
    public void sendFrame(final int minId, final byte[] payload) throws PayloadTooLongException,
                                                                        MinIdException {

        if (payload.length >= 256)
            throw new PayloadTooLongException("payload length: "+payload.length+", max 255.");
        if (minId < 0 || minId >= 64)
            throw new MinIdException("minId out of range: "+minId);

        final MinFrame frame = new MinFrame((byte) (minId & 0xff), payload, 0, false, false);

        final byte[] owb = on_wire_bytes(frame);
        serialInterface.serialWrite(owb);
    }

    /**
     * Queue transport frame. Such frames can be automatically retransmitted in case of error.
     * This is normal way of sending frames.
     *
     * @param minId user defined id of the frame (must be 0 - 63 range)
     * @param payload data to send
     * @throws FifoFullException
     * @throws MinIdException
     * @throws PayloadTooLongException
     */
    public void queueFrame(final int minId, final byte[] payload) throws FifoFullException, MinIdException, PayloadTooLongException {

        if (payload.length > 256)
            throw new PayloadTooLongException("length = "+payload.length+", max 256.");
        if (minId < 0 || minId > 64)
            throw new MinIdException("minId out of range: "+minId);

        if (transport_fifo.size() < transport_fifo_size) {
            final MinFrame frame = new MinFrame((byte) (minId & 0xff), payload, sn_max, true, false);
            //System.out.println("queuing: "+frame);
            transport_fifo.add(frame);
        } else {
            dropped_frames++;
            throw new FifoFullException();
        }
    }

    /**
     * Drives the engine.
     * Sends queued frames, receive incoming traffic, retransmits, sends ACK, does stuff.
     * Must be invoked periodically.
     *
     * @return list of received frames
     */
    public ArrayList<MinFrame> poll() {

        long currentTimeMillis = System.currentTimeMillis();

        final boolean remote_connected = (currentTimeMillis - last_received_anything_ms) < idle_timeout_ms;
        final boolean remote_active = (currentTimeMillis - last_received_frame_ms) < idle_timeout_ms;

        rx_list.clear();
        final byte[] data = serialInterface.serialReadAll();
        if (data.length > 0) {
            rx_bytes(data);
        }

        currentTimeMillis = System.currentTimeMillis();
        final int window_size = (sn_max - sn_min) & 0xff;
        if ((window_size < max_window_size) && transport_fifo.size() > window_size) {
            // # Frames still to send
            final MinFrame frame = transport_fifo_get(window_size);
            frame.seq = (byte) (sn_max & 0xff);

            last_sent_frame_ms = currentTimeMillis;
            frame.last_sent_time = currentTimeMillis;

            transport_fifo_send(frame);
            sn_max = (sn_max + 1) & 0xff;
        } else {
            // # Maybe retransmits
            if ((window_size > 0) && remote_connected) {
                final MinFrame oldest_frame = find_oldest_frame();
                long delta = currentTimeMillis - oldest_frame.last_sent_time;
                if (delta > frame_retransmit_timeout_ms) {
                    //System.out.println("retransmit: "+oldest_frame);
                    transport_fifo_send(oldest_frame);
                }
            }
        }

        // # Periodically transmit ACK
        if (currentTimeMillis - last_sent_ack_time_ms > ack_retransmit_timeout_ms) {
            if (remote_active) {
                send_ack();
            }
        }

        if (((sn_max - sn_max) & 0xff) > window_size) {
            throw new AssertionError();
        }

        return rx_list;
    }

    private void transport_fifo_reset() {

        transport_fifo.clear();
        final long currentMs = System.currentTimeMillis();
        last_received_anything_ms = currentMs;
        last_sent_ack_time_ms = currentMs;
        last_sent_frame_ms = 0l;
        last_received_frame_ms = 0l;
        sn_min = 0;
        sn_max = 0;
        rn = 0;
    }

    private void rx_reset() {
        stashed_rx_dict.clear();
        rx_list.clear();
    }

    private void transport_fifo_pop() {
        transport_fifo.remove(0);
    }

    private MinFrame transport_fifo_get(final int index) {
        return transport_fifo.get(index);
    }

    private void min_frame_received(final byte min_id_control, final byte[] min_payload, final int min_seq) {

        last_received_anything_ms = System.currentTimeMillis();

        if ((min_id_control & 0x80) != 0) {
            if (min_id_control == ACK) {
                final int number_acked = (min_seq - sn_min) & 0xff;
                final int number_in_window = (sn_max - sn_min) & 0xff;
                //System.out.println("acked: "+(min_seq & 0xff)+", number acked: "+number_acked);

                if (number_acked <= number_in_window) {
                    sn_min = min_seq;

                    final int new_number_in_window = (sn_max - sn_min) & 0xff;

                    if (new_number_in_window + number_acked != number_in_window)
                        throw new AssertionError();

                    for (int i = 0; i < number_acked; i++) {
                        transport_fifo_pop();
                    }
                } else {
                    //System.out.println("spurious ack");
                    spurious_acks++;
                }
            } else if (min_id_control == RESET) {
                resets_received++;
                transport_fifo_reset();
                rx_reset();
            } else {
                final MinFrame min_frame = new MinFrame(min_id_control, min_payload, min_seq, true, true);

                last_received_frame_ms = System.currentTimeMillis();

                if (min_seq == rn) {
                    rx_list.add(min_frame);

                    rn = (rn + 1) & 0xff;
                    final Integer key = new Integer(rn);

                    if (stashed_rx_dict.containsKey(key)) {
                        final MinFrame stashed_frame = stashed_rx_dict.remove(key);
                        if (stashed_frame != null) {
                            rx_list.add(stashed_frame);
                            rn = (rn + 1) & 0xff;
                            if (rn == nack_outstanding) {
                                nack_outstanding = None;
                            }
                        }
                    }

                    if ((nack_outstanding == None) && (stashed_rx_dict.size() > 0)) {

                        final TreeSet<Integer> keySet = new TreeSet<Integer>(stashed_rx_dict.keySet());
                        final int earliest_seq = ((Integer) keySet.first()).intValue();

                        if (((earliest_seq - rn) & 0xff) < rx_window_size) {
                            nack_outstanding = earliest_seq;
                            send_nack(earliest_seq);
                        } else {
                            nack_outstanding = None;
                            stashed_rx_dict.clear();
                            send_ack();
                        }
                    } else {
                        send_ack();
                    }
                } else {
                    if (((min_seq - rn) & 0xff) < rx_window_size) {

                        if (nack_outstanding == None) {
                            send_nack(min_seq);
                            nack_outstanding = min_seq;
                        } else {
                            // min_logger.debug("(Outstanding NACK)")
                        }
                        stashed_rx_dict.put(new Integer(min_seq), min_frame);
                    } else {
                        // if min_seq in self._stashed_rx_dict and min_payload !=
                        // self._stashed_rx_dict[min_seq].payload:
                        // min_logger.error("Inconsistency between frame contents")
                        sequence_mismatch_drops++;
                    }
                }
            }
        } else {
            final MinFrame min_frame = new MinFrame(min_id_control, min_payload, 0, false, false);
            rx_list.add(min_frame);
        }
    }

    private void rx_bytes(final byte[] data) {

        for (final byte b : data) {
            if (rx_header_bytes_seen == 2) {
                rx_header_bytes_seen = 0;
                if (b == HEADER_BYTE) {
                    rx_frame_state = State.RECEIVING_ID_CONTROL;
                    continue;
                }
                if (b == STUFF_BYTE) {
                    // # Discard this byte; carry on receiving the next character
                    continue;
                }
                rx_frame_state = State.SEARCHING_FOR_SOF;
                continue;
            }
            if (b == HEADER_BYTE) {
                rx_header_bytes_seen++;
            } else {
                rx_header_bytes_seen = 0;
            }

            switch (rx_frame_state) {
                case SEARCHING_FOR_SOF:
                    break; // no op
                case RECEIVING_ID_CONTROL: {
                    rx_frame_id_control = b;
                    // rx_payload_bytes = 0;
                    if ((rx_frame_id_control & 0x80) != 0) {
                        rx_frame_state = State.RECEIVING_SEQ;
                    } else {
                        rx_frame_state = State.RECEIVING_LENGTH;
                    }
                }
                    break;
                case RECEIVING_SEQ: {
                    rx_frame_seq = b;
                    rx_frame_state = State.RECEIVING_LENGTH;
                }
                    break;
                case RECEIVING_LENGTH: {
                    rx_frame_length = b;
                    rx_control = b;
                    rx_frame_buf = new ByteArrayOutputStream(128); // we expect short messages
                    if (rx_frame_length > 0) {
                        rx_frame_state = State.RECEIVING_PAYLOAD;
                    } else {
                        rx_frame_state = State.RECEIVING_CHECKSUM_3;
                    }
                }
                    break;
                case RECEIVING_PAYLOAD: {
                    // suboptimal
                    rx_frame_buf.write(b);
                    rx_frame_length--;
                    if (rx_frame_length == 0) {
                        rx_frame_state = State.RECEIVING_CHECKSUM_3;
                    }
                }
                    break;
                case RECEIVING_CHECKSUM_3: {
                    rx_frame_checksum = (b << 24) & 0xff000000;
                    rx_frame_state = State.RECEIVING_CHECKSUM_2;
                }
                    break;
                case RECEIVING_CHECKSUM_2: {
                    rx_frame_checksum |= (b << 16) & 0x00ff0000;
                    rx_frame_state = State.RECEIVING_CHECKSUM_1;

                }
                    break;
                case RECEIVING_CHECKSUM_1: {
                    rx_frame_checksum |= (b << 8) & 0x0000ff00;
                    rx_frame_state = State.RECEIVING_CHECKSUM_0;

                }
                    break;
                case RECEIVING_CHECKSUM_0: {
                    rx_frame_checksum |= b & 0xff;
                    final CRC32 crc32 = new CRC32();
                    // crc32.update(prolog, 0, expectedPrologSize - 4); // last 4 bytes reserved for
                    // CRC

                    // ByteBuffer b = ByteBuffer.allocate(4);
                    // b.order(ByteOrder.BIG_ENDIAN); // optional, the initial order of a byte
                    // buffer is always BIG_ENDIAN.
                    // b.putInt((int)crc32.getValue());

                    if ((rx_frame_id_control & 0x80) != 0) {
                        crc32.update(rx_frame_id_control);
                        crc32.update(rx_frame_seq);
                        crc32.update(rx_control);
                        crc32.update(rx_frame_buf.toByteArray());
                    } else {
                        crc32.update(rx_frame_id_control);
                        crc32.update(rx_control);
                        crc32.update(rx_frame_buf.toByteArray());
                    }

                    if (rx_frame_checksum != (int) crc32.getValue()) {
                        //System.out.println("CRC mismatch");
                        rx_frame_state = State.SEARCHING_FOR_SOF;
                    } else {
                        rx_frame_state = State.RECEIVING_EOF;
                    }
                }
                    break;
                case RECEIVING_EOF: {
                    if (b == EOF_BYTE) {
                        // # Frame received OK, pass up frame for handling")
                        min_frame_received(rx_frame_id_control, rx_frame_buf.toByteArray(), rx_frame_seq & 0xff);
                    } else {
                        //System.out.println("No EOF received, dropping frame");
                    }

                    // # Look for next frame
                    rx_frame_state = State.SEARCHING_FOR_SOF;

                }
                    break;
                default:
                    System.out.println("Unexpected state, state machine reset");
                    // # Should never get here but in case we do just reset
                    rx_frame_state = State.SEARCHING_FOR_SOF;
                    break;
            }
        }
    }

    private byte[] on_wire_bytes(final MinFrame frame) {

        byte[] prolog;

        int expectedPrologSize;
        int headerSize;

        if (frame.is_transport) {
            // header (3 bytes + payload + crc32 (4 bytes))
            headerSize = 3;
            expectedPrologSize = headerSize + frame.payload.length + 4;
        } else {
            // header (2 bytes + payload + crc32 (4 bytes))
            headerSize = 2;
            expectedPrologSize = headerSize + frame.payload.length + 4;
        }

        prolog = new byte[expectedPrologSize];

        if (frame.is_transport) {
            prolog[0] = (byte) ((frame.minId | 0x80) & 0xff);
            prolog[1] = frame.seq;
            prolog[2] = (byte) (frame.payload.length & 0xff);
        } else {
            prolog[0] = (byte) (frame.minId & 0xff);
            prolog[1] = (byte) (frame.payload.length & 0xff);
        }

        System.arraycopy(frame.payload, 0, prolog, headerSize, frame.payload.length);

        final CRC32 crc32 = new CRC32();
        crc32.update(prolog, 0, expectedPrologSize - 4); // last 4 bytes reserved for CRC

        final ByteBuffer b = ByteBuffer.allocate(4);
        b.order(ByteOrder.BIG_ENDIAN); // optional, the initial order of a byte buffer is always BIG_ENDIAN.
        b.putInt((int) crc32.getValue());


        System.arraycopy(b.array(), 0, prolog, headerSize + frame.payload.length, 4);

        // 2x size would be always enough
        // if not, ByteArrayOutputStream resizes automatically
        final ByteArrayOutputStream stuffed = new ByteArrayOutputStream(3 + expectedPrologSize * 2);

        stuffed.write(HEADER_BYTE);
        stuffed.write(HEADER_BYTE);
        stuffed.write(HEADER_BYTE);

        int count = 0;

        // escape double 0xaa 0xaa sequence with 0x55 stuff byte

        for (int i = 0; i < prolog.length; i++) {

            stuffed.write(prolog[i]);
            if (prolog[i] == HEADER_BYTE) {
                count++;
                if (count == 2) {
                    stuffed.write(STUFF_BYTE);
                    count = 0;
                }
            } else {
                count = 0;
            }
        }

        stuffed.write(EOF_BYTE);

        byte[] retArray = stuffed.toByteArray();

        // System.out.print(System.currentTimeMillis()+": stuffed");
        // for (byte bt : retArray) {
        //     System.out.print(((bt & 0xff) > 15 ? " 0x":" 0x0")+Integer.toHexString(bt & 0xff));
        // }
        // System.out.println(" ");

        return retArray;
    }

    private MinFrame find_oldest_frame() {
        if (transport_fifo.size() == 0) {
            throw new AssertionError();
        }

        final int window_size = (sn_max - sn_min) & 0xff;
        final long currentTimeMs = System.currentTimeMillis();
        MinFrame oldest_frame = transport_fifo.get(0);
        long longest_elapsed_time = currentTimeMs - oldest_frame.last_sent_time;

        for (int i = 0; i < window_size; i++) {

            final MinFrame frame = transport_fifo.get(i);
            final long elapsed = currentTimeMs - frame.last_sent_time;
            if (elapsed >= longest_elapsed_time) {
                oldest_frame = frame;
                longest_elapsed_time = elapsed;
            }
        }
        return oldest_frame;
    }

    private void transport_fifo_send(final MinFrame frame) {
        final byte[] owb = on_wire_bytes(frame);
        frame.last_sent_time = System.currentTimeMillis();
        serialInterface.serialWrite(owb);
    }

    private void send_ack() {

        final byte[] payload = new byte[] { (byte) (rn & 0xff) };
        final MinFrame ack_frame = new MinFrame(ACK, payload, rn, true, true);

        final byte[] owb = on_wire_bytes(ack_frame);
        last_sent_ack_time_ms = System.currentTimeMillis();
        serialInterface.serialWrite(owb);
        // # For a regular ACK we request no additional retransmits
    }

    private void send_nack(final int to) {

        // # For a NACK we send an ACK but also request some frame retransmits
        final byte[] payload = new byte[] { (byte) (to & 0xff) };
        final MinFrame ack_frame = new MinFrame(ACK, payload, rn, true, true);

        final byte[] owb = on_wire_bytes(ack_frame);
        last_sent_ack_time_ms = System.currentTimeMillis();
        serialInterface.serialWrite(owb);
    }

    private void send_reset() {

        final byte[] payload = new byte[0];
        final MinFrame ack_frame = new MinFrame(RESET, payload, 0, true, true);

        final byte[] owb = on_wire_bytes(ack_frame);
        serialInterface.serialWrite(owb);
    }
}
