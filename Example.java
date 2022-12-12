import java.util.ArrayList;

import pl.skifosoft.minprotocol.MinFrame;
import pl.skifosoft.minprotocol.MinTransport;

public class Example {

    private static final int UserDefinedFrameId = 0x22;

    public static void main(String[] args) {

        byte[] payload = "Hello world".getBytes();

        SerialInterface serialPort = new SerialInterface("/dev/ttyUSB1", 9600);
        MinTransport minHandler = new MinTransport(serialPort);

        try {

            // at the start reset the other end
            minHandler.transportReset();

            while(true) {

                // send frame to receiver
                minHandler.queueFrame(UserDefinedFrameId, payload);

                // polling to handle queues, returns list of received frames
                ArrayList<MinFrame> frames = minHandler.poll();

                // print received frames
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
