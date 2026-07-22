package com.linkextractor.app;

interface IShizukuService {
    /** اجرای دستور shell — خروجی exit code */
    int runCommand(String command);

    /** اجرای دستور shell — خروجی stdout (برای خواندن مقادیر) */
    String runCommandWithOutput(String command);

    void destroy();
}
