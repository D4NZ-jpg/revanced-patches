package app.revanced.extension.d4nz.youtube.subscriptionmanager;

/** Production ownership generation/epoch source used by bind publication and invalidation. */
final class SubscriptionManagerSwipeVersion {
    private long nextGeneration;
    private long epoch;

    synchronized Token next() {
        return new Token(++nextGeneration, epoch);
    }

    synchronized void invalidateAll() {
        epoch++;
    }

    synchronized boolean isCurrent(Token token) {
        return token != null && token.epoch == epoch;
    }

    synchronized boolean matches(Token actual, Token expected) {
        return actual == expected && isCurrent(actual);
    }

    static final class Token {
        final long generation;
        final long epoch;

        private Token(long generation, long epoch) {
            this.generation = generation;
            this.epoch = epoch;
        }
    }
}
