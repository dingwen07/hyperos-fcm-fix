package net.extrawdw.apps.miuisucks.powerkeeper;

interface IPrivilegedService {
    void destroy() = 16777114;
    String enforce(int wechatPolicy) = 1;
    String startFcmProtection() = 2;
    String getMilletNoRestrictValue() = 3;
}
