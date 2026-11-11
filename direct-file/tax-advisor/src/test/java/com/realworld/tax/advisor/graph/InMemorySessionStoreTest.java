package com.realworld.tax.advisor.graph;

class InMemorySessionStoreTest extends SessionStoreContract {

    private final SessionStore store = new InMemorySessionStore();

    @Override
    protected SessionStore store() {
        return store;
    }
}
