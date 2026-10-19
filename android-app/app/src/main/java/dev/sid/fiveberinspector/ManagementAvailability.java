package dev.sid.fiveberinspector;
/** Minimum implementation requirements, never a claim of device qualification or write authority. */
final class ManagementAvailability {
    static String reason(int api, String[] abis) {
        if(api<36)return "This build needs Android 16 or later for card management.";
        if(abis!=null)for(String abi:abis)if("arm64-v8a".equals(abi))return "";
        return "This build needs an ARM64 phone for card management.";
    }
    private ManagementAvailability(){}
}
