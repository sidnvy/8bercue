package dev.sid.fiveberinspector;
/** Self-hosted TCP ADB is not a supported production bootstrap. */
public final class LocalAdbBootstrap {
 public static final class Result {public final boolean ready=false,needsAuthorization=false;public final String description="Connect using your computer and the supplied bootstrap tool.";}
 public static Result start(android.content.Context context){return new Result();}
 private LocalAdbBootstrap(){}
}
