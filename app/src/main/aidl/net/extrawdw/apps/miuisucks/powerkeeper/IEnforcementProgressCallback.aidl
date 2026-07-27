package net.extrawdw.apps.miuisucks.powerkeeper;

interface IEnforcementProgressCallback {
    void onProgress(int completedApps, int totalApps) = 1;
}
