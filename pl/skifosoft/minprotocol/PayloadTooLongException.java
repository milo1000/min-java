package pl.skifosoft.minprotocol;
/**
 * Thrown when payload is longer than 255 bytes.
 */
public class PayloadTooLongException extends MinException{

    public PayloadTooLongException(String message) {
        super(message);
    }

}
