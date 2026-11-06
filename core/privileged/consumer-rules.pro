# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later

# Shizuku starts the worker by class name with its (Context) constructor (SPEC 4.3).
-keep class io.github.akane599.coulomb.core.privileged.worker.CoulombPrivilegedService {
    public <init>(android.content.Context);
}
# The AIDL descriptor and transaction codes must match between the app and worker processes;
# both come from this APK, but keep the interface intact for readable stack traces in reports.
-keep class io.github.akane599.coulomb.core.privileged.ICoulombPrivileged { *; }
-keep class io.github.akane599.coulomb.core.privileged.ICoulombPrivileged$Stub { *; }
