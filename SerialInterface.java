import com.fazecast.jSerialComm.SerialPort;

import pl.skifosoft.minprotocol.MinSerialInterface;

public class SerialInterface implements MinSerialInterface {

    public static final int BAUD_9600 = 57600;
    public static final int BAUD_57600 = 57600;

    private SerialPort comPort;

    public SerialInterface(String ttyName, int baudRate) {

        comPort = SerialPort.getCommPort(ttyName);
        comPort.openPort();
        comPort.setBaudRate(baudRate);
    }

    @Override
    public void serialWrite(byte[] data) {
        comPort.writeBytes(data, data.length);
    }

    @Override
    public byte[] serialReadAll() {

        int bytesAvail = comPort.bytesAvailable();
        if (bytesAvail == -1) {
            System.out.println("WARNING: port not opened.");
            return new byte[0];
        }

        byte[] readBuffer = new byte[comPort.bytesAvailable()];
        int numRead = comPort.readBytes(readBuffer, readBuffer.length);
        if (numRead < readBuffer.length) { // shall never happen
            byte[] tmp = new byte[numRead];
            System.arraycopy(readBuffer, 0, tmp, 0, numRead);
            readBuffer = tmp;
        }
        return readBuffer;
    }

}
