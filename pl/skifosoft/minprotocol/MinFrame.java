package pl.skifosoft.minprotocol;

public final class MinFrame {

    /**
     * Frame
     * Not ment to be created directly, just received.
     */
    MinFrame(byte minId, byte[] payload, int seq, boolean transport, boolean ackOrReset) {

        if(ackOrReset) {
            this.minId = minId;
        }
        else {
            this.minId = (byte)(minId & 0x3f);
        }
        this.payload = payload;
        this.seq = (byte)(seq & 0xff);
        this.is_transport = transport;
    }

    /**
     * Each frame has it's own minId defined by the sender.
     * It's meaning is user-defined, can be used to distinguish
     * different types of frames.
     * Must be 0 - 63 range.
     *
     * @return min-id of the frame
     */
    public int getId() {
        return minId & 0x3f;
    }

    /**
     * @return payload bytes, max payload length is 255
     */
    public byte[] getPayload() {
        return payload;
    }

    byte minId;
    byte seq;
    public byte[] payload;
    boolean is_transport;
    long last_sent_time = -1;

    @Override
    public String toString() {
        return "<MinFrame[t:"+is_transport+"]: id["+getId()+"] seq["+(seq & 0xff)+"]>";
    }
}
