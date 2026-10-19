package dev.sid.fiveberinspector;
import java.util.List;
/** Preserve a protected profile including indirect state changes from enabling another profile. */
final class ProtectedProfilePolicy {
 static void validate(ProfileLifecycleGuard.Operation operation,List<ProfileLifecycleGuard.Row> before,String target,String protectedId){
  ProfileLifecycleGuard.Row protectedRow=ProfileLifecycleGuard.selected(before,protectedId);
  if(protectedId.equals(target))throw new SecurityException("Protected profile cannot be modified");
  if(operation==ProfileLifecycleGuard.Operation.ENABLE&&protectedRow.enabled)
   throw new SecurityException("Enabling another profile would disable the protected profile");
 }
 private ProtectedProfilePolicy(){}
}
