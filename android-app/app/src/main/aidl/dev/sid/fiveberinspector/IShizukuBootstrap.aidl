package dev.sid.fiveberinspector;

/** Fixed connection bootstrap only. No command, path, APDU or card operation input. */
interface IShizukuBootstrap {
    boolean connect() = 0;
    void destroy() = 16777114;
}
