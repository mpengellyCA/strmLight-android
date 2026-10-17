package com.limelight.strmqt;

/**
 * The stream half of pairing: Moonlight pairing with the StrmQt host's
 * Sunshine, with the code on screen as Moonlight's PIN. {@link MoonlightPairer}
 * is the real one; tests use a fake.
 */
public interface StreamPairer {
    enum Result {
        /** Paired just now: the code was entered on the host. */
        PAIRED,
        /** This device was already paired with that Sunshine. */
        ALREADY_PAIRED,
        /**
         * Sunshine already has a pairing attempt waiting from a Moonlight
         * client ("409 A pairing session with this uniqueid already exists").
         */
        SESSION_PENDING,
        /** A different PIN was entered on the host. */
        WRONG_PIN,
        /** Sunshine refused the pairing for another reason. */
        REFUSED,
        /** Sunshine could not be reached at that address and port. */
        UNREACHABLE
    }

    final class Outcome {
        public final Result result;
        /** Moonlight's key for the computer (Sunshine's uniqueid); set when paired. */
        public final String uuid;
        /** The computer's name as Sunshine gives it; set when paired. */
        public final String name;
        /** What went wrong, for the log and the screen; may be null. */
        public final String detail;

        public Outcome(Result result, String uuid, String name, String detail) {
            this.result = result;
            this.uuid = uuid;
            this.name = name;
            this.detail = detail;
        }

        public static Outcome of(Result result, String detail) {
            return new Outcome(result, null, null, detail);
        }

        public boolean paired() {
            return result == Result.PAIRED || result == Result.ALREADY_PAIRED;
        }

        @Override
        public String toString() {
            return result + (detail != null ? " (" + detail + ")" : "");
        }
    }

    /**
     * Pairs with the Sunshine whose base port is `port` on `host`. Blocks: a
     * Moonlight pairing waits on the host until the PIN is entered there.
     * Called on a worker thread; must not throw.
     */
    Outcome pair(String host, int port, String pin) throws InterruptedException;
}
