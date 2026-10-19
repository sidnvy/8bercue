package dev.sid.fiveberinspector;
import java.util.*;
/** Internal grant metadata. Construction is not authority; AuthorizationPolicy checks the trusted evidence catalog. */
final class CardAuthorizationGrant {
 final CardIdentity card;
 final int user;
 final String signer,policy,evidence;
 final Set<String> operations;
 final long expires;
 final boolean revoked;
 CardAuthorizationGrant(CardIdentity card,int user,String signer,String policy,String evidence,Set<String> operations,long expires,boolean revoked){
  if(card==null||user<0||expires<=0||operations==null)throw new IllegalArgumentException("Invalid grant");
  for(String hash:new String[]{signer,policy,evidence})if(hash==null||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid grant binding");
  if(!Set.of("rename","enable","disable","delete","download","notification_cleanup").containsAll(operations))throw new IllegalArgumentException("Unknown grant scope");
  this.card=card;this.user=user;this.signer=signer;this.policy=policy;this.evidence=evidence;this.operations=Set.copyOf(operations);this.expires=expires;this.revoked=revoked;
 }
}
