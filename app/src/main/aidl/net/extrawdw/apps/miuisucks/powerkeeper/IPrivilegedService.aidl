package net.extrawdw.apps.miuisucks.powerkeeper;

import net.extrawdw.apps.miuisucks.powerkeeper.IEnforcementProgressCallback;

interface IPrivilegedService {
    void destroy() = 16777114;
    String enforce(in String[] aurogonPackages, in String[] policyPackages, in boolean[] autostartManaged, in boolean[] autostartEnabled, in boolean[] dozeManaged, in int[] dozePolicies, in int[] targetUserIds, String trigger) = 1;
    String startFcmProtection(in String[] aurogonPackages, String trigger) = 2;
    String getMilletNoRestrictValue(String trigger) = 3;
    String listAndroidUsers(String trigger) = 4;
    void attachLogPath(String logPath) = 5;
    String reconcileAurogon(in String[] aurogonPackages, String trigger) = 6;
    String applyAppPolicy(String packageName, boolean autostartManaged, boolean autostartEnabled, boolean dozeManaged, int dozePolicy, in int[] targetUserIds, String trigger) = 7;
    String enforceBatched(in String[] aurogonPackages, in String[] policyPackages, in boolean[] autostartManaged, in boolean[] autostartEnabled, in boolean[] dozeManaged, in int[] dozePolicies, in int[] targetUserIds, String trigger, IEnforcementProgressCallback progressCallback) = 8;
    String unstop(in String[] packageNames, in int[] targetUserIds, String trigger) = 9;
    String configureFcmPolling(long intervalMillis, boolean fcmReconnectEnabled, String trigger) = 10;
}
