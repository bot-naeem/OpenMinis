package com.openminis.app.sandbox.offload

/**
 * Safety guard for superuser (Root) commands.
 *
 * Sits directly before privileged process invocation to enforce hard boundaries
 * against catastrophic commands that can permanently brick the device, corrupt
 * raw storage partitions, or wipe user data, regardless of user permissions.
 */
object RootCommandGuard {

    sealed class Decision {
        object Allowed : Decision()
        data class Blocked(val code: String, val reason: String) : Decision()
    }

    private val BLOCK_DEVICE_PATTERNS = listOf(
        Regex("""/dev/block\b""", RegexOption.IGNORE_CASE),
        Regex("""/dev/mem\b""", RegexOption.IGNORE_CASE),
        Regex("""/dev/kmsg\b""", RegexOption.IGNORE_CASE),
        Regex("""/dev/mtd\b""", RegexOption.IGNORE_CASE),
        Regex("""/proc/sysrq-trigger\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdd\b.*?\bof\s*=\s*/dev/""", RegexOption.IGNORE_CASE),
        Regex("""\b(mkfs(\.[a-z0-9]+)?|fdisk|parted|sgdisk|gdisk|flash_image|bootctl)\b""", RegexOption.IGNORE_CASE),
    )

    private val MASS_DELETION_PATTERNS = listOf(
        // rm -rf or rm -r on root or top-level system/data trees
        Regex(
            """\brm\s+-[a-zA-Z]*[rR][a-zA-Z]*\s+["']?(/|/\*|/data/?(\*)?|/system/?(\*)?|/data/data/?(\*)?|/data/user/?(\*)?)["']?(\s*($|[;&|]))""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""\b(wipe\s+data|factory_reset)\b""", RegexOption.IGNORE_CASE),
    )

    private val SYSTEM_REMOUNT_PATTERNS = listOf(
        // remounting read-only root or system partitions as writable
        Regex("""\bmount\s+.*?\bremount\b.*?\brw\b.*?(/system|/vendor|/product|/odm|/apex|/)(\s*($|[;&|]))""", RegexOption.IGNORE_CASE),
        Regex("""\bmount\s+-o\s+.*remount.*rw.*""", RegexOption.IGNORE_CASE),
    )

    private val BOOTLOADER_REBOOT_PATTERNS = listOf(
        // Rebooting into offline / flash modes that leave an unattended phone stranded
        Regex("""\breboot\s+(bootloader|fastboot|edl|recovery)\b""", RegexOption.IGNORE_CASE),
    )

    private val SELINUX_DISABLE_PATTERNS = listOf(
        Regex("""\bsetenforce\s+0\b"""),
    )

    /**
     * Inspect a raw shell command string before it reaches `su -c`.
     *
     * @return [Decision.Allowed] if the command does not violate any safety policies,
     * or [Decision.Blocked] with a typed reason if it attempts high-risk / bricking operations.
     */
    fun check(command: String): Decision {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return Decision.Allowed

        for (pattern in BLOCK_DEVICE_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return Decision.Blocked(
                    code = "BLOCKED_BRICK_RISK",
                    reason = "Refused: command accesses or writes to raw block devices or system memory nodes (/dev/block, /dev/mem, etc.). Blocked by RootCommandGuard to prevent bricking the phone.",
                )
            }
        }

        for (pattern in MASS_DELETION_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return Decision.Blocked(
                    code = "BLOCKED_DATA_WIPE",
                    reason = "Refused: command attempts catastrophic recursive deletion of root or core system/data partitions. Blocked by RootCommandGuard.",
                )
            }
        }

        for (pattern in SYSTEM_REMOUNT_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return Decision.Blocked(
                    code = "BLOCKED_SYSTEM_REMOUNT",
                    reason = "Refused: command attempts to remount system partitions as writable. Blocked by RootCommandGuard.",
                )
            }
        }

        for (pattern in BOOTLOADER_REBOOT_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return Decision.Blocked(
                    code = "BLOCKED_OFFLINE_REBOOT",
                    reason = "Refused: command attempts to reboot into bootloader, fastboot, EDL, or recovery mode. Blocked by RootCommandGuard.",
                )
            }
        }

        for (pattern in SELINUX_DISABLE_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return Decision.Blocked(
                    code = "BLOCKED_SELINUX_TAMPER",
                    reason = "Refused: command attempts to globally disable SELinux (setenforce 0). Blocked by RootCommandGuard.",
                )
            }
        }

        return Decision.Allowed
    }
}
