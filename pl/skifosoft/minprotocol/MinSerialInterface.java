package pl.skifosoft.minprotocol;

public interface MinSerialInterface {

    /**
     * @param data
     */
    void serialWrite(byte[] data);

    /**
     * @return
     */
    byte[] serialReadAll();

}
