package pl.skifosoft.minprotocol;

/**
 * Thrown when MinId out of range. Must be 0 - 63.
 */
public class MinIdException extends MinException{

    public MinIdException(String message) {
        super(message);
    }
}
