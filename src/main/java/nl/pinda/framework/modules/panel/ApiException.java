package nl.pinda.framework.modules.panel;

/** Een nette foutmelding voor het paneel, met de HTTP-status die erbij hoort. */
final class ApiException extends Exception {

    private final int status;

    ApiException(int status, String message) {
        super(message, null, false, false);
        this.status = status;
    }

    int status() {
        return status;
    }

    static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    static ApiException forbidden(String message) {
        return new ApiException(403, message);
    }

    static ApiException notFound(String message) {
        return new ApiException(404, message);
    }
}
