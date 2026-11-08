// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// The privileged worker's whole surface (SPEC 4.3, 19.1). Every method is typed: integer ids
// select from fixed allowlists in WorkerProtocol.kt, and the few string arguments (package
// names, settings keys, sysfs paths) are validated by the worker before use. There is no
// free-form shell. Large outputs are written to a caller-supplied pipe, never returned.
package io.github.akane599.coulomb.core.privileged;

interface ICoulombPrivileged {
    // Reserved by the Shizuku UserService contract: Shizuku sends this transaction
    // (ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy = 16777115 = FIRST_CALL + 16777114)
    // before it removes the service. The worker exits its process.
    void destroy() = 16777114;

    // Worker code version (the APK versionCode it was loaded from). Mismatch: unbind with
    // remove = true and rebind (SPEC 4.3).
    long getVersion() = 1;

    // Process uid of the worker: 0 = root, 2000 = shell.
    int getUid() = 2;

    // SELinux context of the worker process, for the capability report.
    String getSeLinuxContext() = 3;

    // `dumpsys batterystats` with one allowlisted mode (WorkerProtocol.BatteryStatsMode).
    // historyStart is used only by HISTORY_START and CHECKIN_HISTORY. Returns a WorkerProtocol.Status code after
    // the service finished writing; the worker closes its copy of `pipe` before returning.
    int dumpBatteryStats(int mode, long historyStart, in ParcelFileDescriptor pipe) = 10;

    // `dumpsys <service>` for one allowlisted service (WorkerProtocol.Service).
    int dumpService(int service, in ParcelFileDescriptor pipe) = 11;

    // Fixed `cmd` queries from the read group (WorkerProtocol.Query); output goes to `pipe`.
    // `packageName` is required by APP_OPS and ignored otherwise.
    int runQuery(int query, String packageName, in ParcelFileDescriptor pipe) = 12;

    // The four small reads below return their value directly and a WorkerStatusCode in status[0]
    // (an `out int[1]` the caller allocates), so no hidden exception type has to cross binder.

    // `settings get <namespace> <key>` for battery-related keys only (ADR-0009). Returns null
    // with status OK when the key is unset.
    String getSetting(int namespace, String key, out int[] status) = 13;

    // Names (never values) of battery-related keys in a settings namespace (ADR-0009).
    String[] listSettingKeys(int namespace, out int[] status) = 14;

    // Reads one file under an allowlisted sysfs root (WorkerProtocol.SysfsRoot), at most
    // WorkerLimits.MAX_SYSFS_BYTES. EACCES gives ACCESS_DENIED so the app can label the feature
    // "needs root" (SPEC 4.5); ENOENT gives NOT_FOUND.
    byte[] readSysfs(int root, String relativePath, out int[] status) = 15;

    // Entry names directly under an allowlisted sysfs root or one of its subdirectories.
    String[] listSysfs(int root, String relativeDir, out int[] status) = 16;

    // Root kernel sources (ADR-0028, ADR-0029). Reads boot_id, then S25, S26 and S9 as the
    // `sources` bits ask (KernelPaths.READ_*), into `pipe` as length-framed records with a final
    // `#end`. Paths are fixed in the worker. Returns INVALID_ARGUMENT for unknown bits.
    int readKernelFiles(int sources, in ParcelFileDescriptor pipe) = 17;

    // S27: the allowlisted suspend and resume records of /dev/kmsg, at most
    // WorkerLimits.MAX_KMSG_KEPT per call. `afterSeq` (-1 for none) applies only when `bootKey`
    // matches the running kernel's boot; otherwise the read starts at the ring's oldest record.
    // ACCESS_DENIED when the kernel log cannot be opened (shell, or a closed path).
    int readKernelLog(long bootKey, long afterSeq, in ParcelFileDescriptor pipe) = 18;

    // Bootstrap group (SPEC 4.4): grants one of three permissions to Coulomb's own package.
    int grantPermission(int permission) = 30;

    // Bootstrap group: `appops set <coulomb> GET_USAGE_STATS allow`.
    int allowUsageStatsAppOp() = 31;

    // Transport test for the P1 gate ("a 20 MB dump streams without OOM") and the protocol F drills:
    // writes a deterministic pattern of `bytes`, stops early after `truncateAt` bytes while still
    // returning OK (a stream that ends too soon), and sleeps `chunkDelayMs` (at most 1000) after each
    // 64 KB chunk so a drill can stop Shizuku mid-stream. Runs no shell operation; refused unless
    // the APK is debuggable.
    int streamTestPattern(long bytes, long truncateAt, int chunkDelayMs, in ParcelFileDescriptor pipe) = 90;
}
