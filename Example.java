import java.util.ArrayList;

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
        SerialInterface serialPort = new SerialInterface("/dev/ttyUSB1", 9600);

        // create our host object
        MinTransport minHandler = new MinTransport(serialPort);

        try {

            // at the start reset the other end
            // so itt won't reject our frames
            minHandler.transportReset();

            // polling loop, probably you need separate thread for this
            while(true) {

                // send frame to receiver
                minHandler.queueFrame(UserDefinedFrameId, payload);

                // polling to handle queues, returns list of received frames
                ArrayList<MinFrame> frames = minHandler.poll();

                // print received frames
                // you would receive 0 frames here if the other end does not send min-protocol frames
                // wich is OK if you plan to have one-way communication
                System.out.println("frames count: "+frames.size());
                for (MinFrame frame : frames) {
                    System.out.println(frame);
                    System.out.println("payload<"+new String(frame.getPayload())+">");
                }

                // yeld
                Thread.sleep(2000);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}
