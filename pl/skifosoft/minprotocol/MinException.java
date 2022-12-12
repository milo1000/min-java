package pl.skifosoft.minprotocol;
/**
 * Base class for MIN exceptions
 */
public abstract class MinException extends Exception {

    public MinException(String message) {
        super(message);
    }

    public MinException() {
        super();
    }
}
