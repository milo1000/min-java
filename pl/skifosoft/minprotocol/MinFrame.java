package pl.skifosoft.minprotocol;

public final class MinFrame {

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
     * @return
     */
    public int getId() {
        return minId & 0x3f;
    }

    /**
     * @return
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
