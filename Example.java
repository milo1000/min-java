import java.util.ArrayList;

import pl.skifosoft.minprotocol.FifoFullException;
import pl.skifosoft.minprotocol.MinFrame;
import pl.skifosoft.minprotocol.MinTransport;

public class Example {

    // This is user-defined id of the frame,
    // so you can easy distinguish type of frame without
    // parsing payload
    // It must be beetween 0 and 63
    private static final int UserDefinedFrameId = 0x22;

    public static void main(String[] args) {

        // get some payload to send to other end
        byte[] payload = "Hello world".getBytes();

        // instantiate serial port interface
        // implementation backed by jSerialComm
        SerialInterface serialPort = new SerialInterface("/dev/ttyUSB0", 9600);

        // create our host object
        MinTransport minHandler = new MinTransport(serialPort);
        minHandler.setRetransmitTimeout((args.length > 0) ? Integer.decode(args[0]).intValue() : 400);
        minHandler.setKeepAliveTimeout(1000);

        try {

            // at the start reset the other end
            // so it won't reject our frames
            System.out.println("Sending reset...");
            Thread.sleep(2000); // wait a bit, some arduinos reset after connection over usb
            minHandler.transportReset();
            System.out.println("...sent");

            long lastSendHello = System.currentTimeMillis();

            // polling loop, probably you need separate thread for this
            while(true) {

                // send frame to receiver

                long nowSendHello = System.currentTimeMillis();
                if ((nowSendHello - lastSendHello) > 2000) { // send Hello world every 2 seconds
                    try {
                        System.out.println("sending Hello world");
                        lastSendHello = nowSendHello;
                        minHandler.queueFrame(UserDefinedFrameId, payload);
                    }
                    catch (FifoFullException e) {
                        System.out.println("FIFO full, other end does not receive frames");
                        minHandler.transportReset(); // last resort

                    }
                }

                // polling to handle queues, returns list of received frames
                ArrayList<MinFrame> frames = minHandler.poll();

                // print received frames
                // you would receive 0 frames here if the other end does not send min-protocol frames
                // wich is OK if you plan to have one-way communication
                for (MinFrame frame : frames) {
                    System.out.print(frame);
                    System.out.println(" payload<"+new String(frame.getPayload())+">");
                }

                // yeld
                Thread.sleep(50);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}
