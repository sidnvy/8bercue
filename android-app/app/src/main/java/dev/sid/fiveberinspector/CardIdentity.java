package dev.sid.fiveberinspector;
/** Complete internal identity. Never expose the digest as a user permission or raw EID. */
final class CardIdentity {
 final String sha256;
 CardIdentity(String sha256){if(sha256==null||!sha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Complete card identity required");this.sha256=sha256;}
 @Override public boolean equals(Object other){return other instanceof CardIdentity&&sha256.equals(((CardIdentity)other).sha256);}
 @Override public int hashCode(){return sha256.hashCode();}
 @Override public String toString(){return "CardIdentity[redacted]";}
}
