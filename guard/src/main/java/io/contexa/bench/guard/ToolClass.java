package io.contexa.bench.guard;

/**
 * The importance class of a tool, which decides how the tool is declared to the engine (the {@code @Protectable} of the
 * gateway method of the class) and how the request looks to it (the HTTP method of the tool's endpoint).
 */
public enum ToolClass {

    /** Reference data anyone may read (the frozen file says data PUBLIC, effect NONE): not sensitive, no wait for a verdict. */
    READ_PUBLIC("GET", "action READ, non-sensitive"),
    READ("GET", "action READ"),
    WRITE("POST", "action WRITE"),
    MONEY("POST", "action WRITE, category PAYMENTS, sensitive"),
    DELETE("DELETE", "action DELETE, sensitive"),
    EXTERNAL("POST", "action EXPORT, exports data, sensitive"),
    CREDENTIAL("POST", "action ADMIN, privileged, sensitive");

    private final String httpMethod;
    private final String declaration;

    ToolClass(String httpMethod, String declaration) {
        this.httpMethod = httpMethod;
        this.declaration = declaration;
    }

    /** The method of the tool's endpoint: a read is a GET, a deletion a DELETE, every other change a POST. */
    public String httpMethod() {
        return httpMethod;
    }

    /** What the gateway method of the class declares to the engine, in words. */
    public String declaration() {
        return declaration;
    }
}
