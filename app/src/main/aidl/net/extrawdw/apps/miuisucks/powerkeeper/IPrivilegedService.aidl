package net.extrawdw.apps.miuisucks.powerkeeper;

interface IPrivilegedService {
    void destroy() = 16777114;
    String enforce(int wechatPolicy, in int[] targetUserIds) = 1;
    String startFcmProtection() = 2;
    String getMilletNoRestrictValue() = 3;
    String listAndroidUsers() = 4;
}
