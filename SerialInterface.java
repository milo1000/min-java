import com.fazecast.jSerialComm.SerialPort;

import pl.skifosoft.minprotocol.MinSerialInterface;

public class SerialInterface implements MinSerialInterface {

    private static final byte[] emptyBuffer = new byte[0];

    public static final int BAUD_9600 = 9600;
    public static final int BAUD_57600 = 57600;

    private SerialPort comPort;

    // for specifics of this implementation see jSerialComm
    // https://fazecast.github.io/jSerialComm/

    public SerialInterface(String ttyName, int baudRate) {

        comPort = SerialPort.getCommPort(ttyName);
        comPort.setBaudRate(baudRate);
        comPort.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING | SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
        comPort.setNumDataBits(8);
        comPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        comPort.setParity(SerialPort.NO_PARITY);
        comPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        comPort.openPort();
    }

    @Override
    public void serialWrite(byte[] data) {
        int ret = comPort.writeBytes(data, data.length);
        if (ret == -1)
            throw new SerialPortException("write failed");
        if (ret != data.length) {
            throw new SerialPortException("written: "+ret+", but expected: "+data.length);
        }
    }

    @Override
    public byte[] serialReadAll() {

        int bytesAvail = comPort.bytesAvailable();
        if (bytesAvail == -1) {
            throw new SerialPortException("port not opened");
        }

        if (bytesAvail == 0) {  // fail fast
            return emptyBuffer;
        }

        byte[] readBuffer = new byte[comPort.bytesAvailable()];
        int numRead = comPort.readBytes(readBuffer, readBuffer.length);

        if (numRead == -1) {
            throw new SerialPortException("error reading from the port");
        }

        if (numRead < readBuffer.length) { // shall never happen
            throw new SerialPortException("expected "+readBuffer.length+" bytes but got "+numRead);
        }
        return readBuffer;
    }
}
