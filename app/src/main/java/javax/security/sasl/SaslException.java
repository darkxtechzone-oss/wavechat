package javax.security.sasl;

import java.io.IOException;

/** Android haina javax.security.sasl; hii ni mbadala mdogo ili MongoDB driver (SCRAM) ifanye kazi. */
public class SaslException extends IOException {
    private static final long serialVersionUID = 4579784287983423626L;
    private Throwable _exception;

    public SaslException() {
        super();
    }

    public SaslException(String detail) {
        super(detail);
    }

    public SaslException(String detail, Throwable ex) {
        super(detail);
        if (ex != null) {
            initCause(ex);
        }
    }

    @Override
    public Throwable getCause() {
        return _exception;
    }

    @Override
    public Throwable initCause(Throwable cause) {
        super.initCause(cause);
        _exception = cause;
        return this;
    }

    @Override
    public String toString() {
        String s = super.toString();
        if (_exception != null && _exception != this) {
            s += " [Caused by " + _exception.toString() + "]";
        }
        return s;
    }
}
