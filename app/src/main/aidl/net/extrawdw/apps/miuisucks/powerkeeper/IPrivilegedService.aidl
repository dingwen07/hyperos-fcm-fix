package net.extrawdw.apps.miuisucks.powerkeeper;

interface IPrivilegedService {
    void destroy() = 16777114;
    String enforce(int wechatPolicy, in int[] targetUserIds, String trigger) = 1;
    String startFcmProtection(String trigger) = 2;
    String getMilletNoRestrictValue(String trigger) = 3;
    String listAndroidUsers(String trigger) = 4;
    void attachLogPath(String logPath) = 5;
}
