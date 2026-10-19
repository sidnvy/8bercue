package dev.sid.fiveberinspector;
import java.util.*;
/** A private identity or user checkbox cannot introduce issuer-authority evidence.
 * Production catalog inputs require reviewed provenance; current release policy has no qualified entries.
 */
final class AuthorizationPolicy {
 private final String digest;
 private final Set<String> acceptedEvidence;
 AuthorizationPolicy(String digest,Set<String> acceptedEvidence){
  if(digest==null||!digest.matches("[a-f0-9]{64}")||acceptedEvidence==null)throw new IllegalArgumentException("Invalid policy");
  for(String entry:acceptedEvidence)if(entry==null||!entry.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid evidence");
  this.digest=digest;this.acceptedEvidence=Set.copyOf(acceptedEvidence);
 }
 boolean allows(CardAuthorizationGrant grant,CardIdentity card,int user,String signer,String operation,long now){
  return grant!=null&&card!=null&&now>=0&&now<grant.expires&&!grant.revoked
    &&user==0&&user==grant.user&&card.equals(grant.card)&&grant.signer.equals(signer)
    &&digest.equals(grant.policy)&&acceptedEvidence.contains(grant.evidence)&&grant.operations.contains(operation);
 }
}
