package com.logscope.ai;

interface ILogScopeService {
    void startLogcat() = 1;
    String[] readAvailable(int maxLines) = 2;
    void stopLogcat() = 3;
    String getDeviceSummary() = 4;
    void destroy() = 16777114;
}
