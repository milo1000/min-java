package pl.skifosoft.minprotocol;
/**
 * This interface must be implemented to enable simple serial port I/O.
 */
public interface MinSerialInterface {

    /**
     * Write array of bytes to the serial port. Blocking.
     *
     * @param data
     */
    void serialWrite(byte[] data);

    /**
     * Read all available bytes from serial port. Non blocking.
     *
     * @return array of bytes, should retur 0-length array if no data available.
     */
    byte[] serialReadAll();

}
