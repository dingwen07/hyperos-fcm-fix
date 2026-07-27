package net.extrawdw.apps.miuisucks.powerkeeper;

import net.extrawdw.apps.miuisucks.powerkeeper.IEnforcementProgressCallback;

interface IPrivilegedService {
    void destroy() = 16777114;
    String enforce(in String[] aurogonPackages, in String[] managedAurogonPackages, in String[] policyPackages, in int[] autostartModes, in int[] dozePolicies, in int[] targetUserIds, String trigger) = 1;
    String startFcmProtection(in String[] aurogonPackages, in String[] managedAurogonPackages, String trigger) = 2;
    String getMilletNoRestrictValue(String trigger) = 3;
    String listAndroidUsers(String trigger) = 4;
    void attachLogPath(String logPath) = 5;
    String reconcileAurogon(in String[] aurogonPackages, in String[] managedAurogonPackages, String trigger) = 6;
    String applyAppPolicy(String packageName, int autostartMode, int dozePolicy, in int[] targetUserIds, String trigger) = 7;
    String enforceBatched(in String[] aurogonPackages, in String[] managedAurogonPackages, in String[] policyPackages, in int[] autostartModes, in int[] dozePolicies, in int[] targetUserIds, String trigger, IEnforcementProgressCallback progressCallback) = 8;
}
