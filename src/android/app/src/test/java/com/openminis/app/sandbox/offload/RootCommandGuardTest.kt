package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying that [RootCommandGuard] reliably intercepts destructive / brick-risk
 * commands while safely allowing legitimate root maintenance and inspection commands.
 */
class RootCommandGuardTest {

    @Test
    fun `blocks block device and partition brick threats`() {
        val dangerous = listOf(
            "dd if=/dev/zero of=/dev/block/bootdevice/by-name/boot",
            "mkfs.ext4 /dev/block/sda",
            "fdisk /dev/block/mmcblk0",
            "cat /dev/mem",
            "echo c > /proc/sysrq-trigger",
            "dd if=/tmp/bad.img of=/dev/block/mmcblk0p1",
            "flash_image boot /data/local/tmp/boot.img",
        )

        for (cmd in dangerous) {
            val decision = RootCommandGuard.check(cmd)
            assertTrue("Expected command to be blocked: $cmd", decision is RootCommandGuard.Decision.Blocked)
            assertEquals("BLOCKED_BRICK_RISK", (decision as RootCommandGuard.Decision.Blocked).code)
        }
    }

    @Test
    fun `blocks catastrophic mass deletion commands`() {
        val dangerous = listOf(
            "rm -rf /",
            "rm -rf /*",
            "rm -rf /data",
            "rm -rf /data/",
            "rm -fr /data",
            "rm -r /system",
            "rm -rf /system/",
            "rm -rf /data/data",
            "rm -rf '/data'",
            "rm -rf \"/system\"",
            "wipe data",
        )

        for (cmd in dangerous) {
            val decision = RootCommandGuard.check(cmd)
            assertTrue("Expected command to be blocked: $cmd", decision is RootCommandGuard.Decision.Blocked)
            assertEquals("BLOCKED_DATA_WIPE", (decision as RootCommandGuard.Decision.Blocked).code)
        }
    }

    @Test
    fun `blocks read-write remount of system partitions`() {
        val dangerous = listOf(
            "mount -o remount,rw /system",
            "mount -o remount,rw /",
            "mount -o remount,rw /vendor",
        )

        for (cmd in dangerous) {
            val decision = RootCommandGuard.check(cmd)
            assertTrue("Expected command to be blocked: $cmd", decision is RootCommandGuard.Decision.Blocked)
            assertEquals("BLOCKED_SYSTEM_REMOUNT", (decision as RootCommandGuard.Decision.Blocked).code)
        }
    }

    @Test
    fun `blocks offline bootloader reboots and selinux tamper`() {
        val reboots = listOf(
            "reboot bootloader",
            "reboot fastboot",
            "reboot edl",
            "reboot recovery",
        )
        for (cmd in reboots) {
            val decision = RootCommandGuard.check(cmd)
            assertTrue("Expected command to be blocked: $cmd", decision is RootCommandGuard.Decision.Blocked)
            assertEquals("BLOCKED_OFFLINE_REBOOT", (decision as RootCommandGuard.Decision.Blocked).code)
        }

        val selinuxDecision = RootCommandGuard.check("setenforce 0")
        assertTrue(selinuxDecision is RootCommandGuard.Decision.Blocked)
        assertEquals("BLOCKED_SELINUX_TAMPER", (selinuxDecision as RootCommandGuard.Decision.Blocked).code)
    }

    @Test
    fun `allows legitimate system inspection and specific single file removal`() {
        val legitimate = listOf(
            "getprop ro.build.version.release",
            "logcat -d -b all | grep -i avc",
            "dumpsys battery",
            "ls -la /data/data/com.tencent.mm",
            "cat /data/system/packages.xml",
            "rm /data/local/tmp/test.log",
            "rm -f /data/local/tmp/temp.txt",
            "sqlite3 /data/data/com.example/databases/app.db \"SELECT * FROM users;\"",
            "ps -ef | grep minis",
        )

        for (cmd in legitimate) {
            val decision = RootCommandGuard.check(cmd)
            assertTrue("Expected command to be allowed: $cmd, got $decision", decision is RootCommandGuard.Decision.Allowed)
        }
    }
}
