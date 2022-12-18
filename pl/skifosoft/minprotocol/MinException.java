package pl.skifosoft.minprotocol;
/**
 * Base class for MIN exceptions.
 * Can be subclassed to throw custom exceptions from MinSerialInterface methods.
 */
public abstract class MinException extends Exception {

    public MinException(String message) {
        super(message);
    }

    public MinException() {
        super();
    }
}
