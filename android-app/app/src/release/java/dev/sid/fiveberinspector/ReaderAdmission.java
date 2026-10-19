package dev.sid.fiveberinspector;
/** Standard GetEID discovery is read-only and does not grant management permission. */
final class ReaderAdmission {
 static boolean accepts(String digest){return digest!=null&&digest.matches("[a-fA-F0-9]{64}");}
 static boolean known(String digest){return accepts(digest);}
 static String label(String digest){if(!accepts(digest))throw new SecurityException("Invalid identity");return "eUICC";}
 private ReaderAdmission(){}
}
